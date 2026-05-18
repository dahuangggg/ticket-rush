package dev.dahuangggg.ticketrush.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import dev.dahuangggg.ticketrush.dto.reminder.ReminderToolResult;
import dev.dahuangggg.ticketrush.dto.sku.TicketSkuDTO;
import dev.dahuangggg.ticketrush.entity.RushReminder;
import dev.dahuangggg.ticketrush.entity.TicketSku;
import dev.dahuangggg.ticketrush.exception.TicketSkuNotFoundException;
import dev.dahuangggg.ticketrush.mapper.RushReminderMapper;
import dev.dahuangggg.ticketrush.service.TicketSkuService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@SuppressWarnings({"unchecked", "rawtypes"})
class RushReminderServiceImplTest {

    private RushReminderMapper mapper;
    private TicketSkuService skuService;
    private StringRedisTemplate redis;
    private ZSetOperations<String, String> zset;
    private RushReminderServiceImpl svc;
    private Clock clock;

    private static final long NOW_EPOCH_MS = 1_700_000_000_000L; // 2023-11-14 22:13:20Z

    @BeforeAll
    static void initMybatisPlusTableInfo() {
        MybatisConfiguration cfg = new MybatisConfiguration();
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(cfg, "");
        assistant.setCurrentNamespace("dev.dahuangggg.ticketrush.mapper.RushReminderMapper");
        TableInfoHelper.initTableInfo(assistant, RushReminder.class);
    }

    @BeforeEach
    void setUp() {
        mapper = mock(RushReminderMapper.class);
        skuService = mock(TicketSkuService.class);
        redis = mock(StringRedisTemplate.class);
        zset = mock(ZSetOperations.class);
        when(redis.opsForZSet()).thenReturn(zset);
        clock = Clock.fixed(Instant.ofEpochMilli(NOW_EPOCH_MS), ZoneId.of("UTC"));
        svc = new RushReminderServiceImpl(mapper, skuService, redis, clock, 20);
    }

    private TicketSkuDTO skuStartingInMinutes(long minutesFromNow, int status) {
        return new TicketSkuDTO(
                42L, 1001L, "VIP", 50000L, 100,
                LocalDateTime.now(clock).plusMinutes(minutesFromNow),
                LocalDateTime.now(clock).plusMinutes(minutesFromNow + 120),
                4, status);
    }

    @Test
    void setReminder_rejectsWhenSkuMissing() {
        when(skuService.getSkuDetail(99L)).thenThrow(new TicketSkuNotFoundException(99L));
        ReminderToolResult r = svc.setReminder(1L, 99L, 5);
        assertThat(r.isOk()).isFalse();
        assertThat(r.getMessage()).contains("票档不存在");
        verifyNoInteractions(zset);
    }

    @Test
    void setReminder_rejectsWhenAlreadyOnSale() {
        when(skuService.getSkuDetail(42L))
                .thenReturn(skuStartingInMinutes(-1, TicketSku.STATUS_ON_SALE));
        ReminderToolResult r = svc.setReminder(1L, 42L, 5);
        assertThat(r.isOk()).isFalse();
        assertThat(r.getMessage()).contains("已开售");
    }

    @Test
    void setReminder_rejectsWhenLeadCoversFullWindow() {
        when(skuService.getSkuDetail(42L))
                .thenReturn(skuStartingInMinutes(3, TicketSku.STATUS_NOT_STARTED));
        ReminderToolResult r = svc.setReminder(1L, 42L, 5);
        assertThat(r.isOk()).isFalse();
        assertThat(r.getMessage()).contains("距离开抢时间太近");
    }

    @Test
    void setReminder_rejectsWhenUserAtCapacity() {
        when(skuService.getSkuDetail(42L))
                .thenReturn(skuStartingInMinutes(60, TicketSku.STATUS_NOT_STARTED));
        when(mapper.selectOne(any(Wrapper.class))).thenReturn(null);
        when(mapper.selectCount(any(Wrapper.class))).thenReturn(20L);
        ReminderToolResult r = svc.setReminder(1L, 42L, 5);
        assertThat(r.isOk()).isFalse();
        assertThat(r.getMessage()).contains("提醒数量已达上限");
    }

    @Test
    void setReminder_clampsLeadMinutesAndInsertsNew() {
        when(skuService.getSkuDetail(42L))
                .thenReturn(skuStartingInMinutes(2000, TicketSku.STATUS_NOT_STARTED));
        when(mapper.selectOne(any(Wrapper.class))).thenReturn(null);
        when(mapper.selectCount(any(Wrapper.class))).thenReturn(0L);
        when(mapper.insert(any(RushReminder.class))).thenAnswer(inv -> {
            ((RushReminder) inv.getArgument(0)).setId(100L);
            return 1;
        });

        ReminderToolResult r = svc.setReminder(1L, 42L, 9999);

        ArgumentCaptor<RushReminder> cap = ArgumentCaptor.forClass(RushReminder.class);
        verify(mapper).insert(cap.capture());
        assertThat(cap.getValue().getLeadMinutes()).isEqualTo(1440);
        assertThat(cap.getValue().getSkuId()).isEqualTo(42L);
        assertThat(cap.getValue().getEventId()).isEqualTo(1001L);
        assertThat(r.isOk()).isTrue();
        verify(zset).add(eq("ai:reminder:zset"), eq("100"), anyDouble());
    }

    @Test
    void setReminder_idempotentlyUpdatesExisting() {
        when(skuService.getSkuDetail(42L))
                .thenReturn(skuStartingInMinutes(60, TicketSku.STATUS_NOT_STARTED));
        RushReminder existing = RushReminder.builder().id(77L).userId(1L).skuId(42L).eventId(1001L)
                .leadMinutes(5).status("PENDING").build();
        when(mapper.selectOne(any(Wrapper.class))).thenReturn(existing);

        ReminderToolResult r = svc.setReminder(1L, 42L, 10);

        assertThat(r.isOk()).isTrue();
        assertThat(r.getReminderId()).isEqualTo(77L);
        verify(mapper, never()).insert(any(RushReminder.class));
        verify(mapper).updateById(argThat((RushReminder u) ->
                u.getId().equals(77L) && u.getLeadMinutes() == 10 && "PENDING".equals(u.getStatus())));
        verify(zset).add(eq("ai:reminder:zset"), eq("77"), anyDouble());
    }

    @Test
    void fire_returnsFalseWhenAlreadyFired() {
        when(mapper.update(any(), any(Wrapper.class))).thenReturn(0);
        assertThat(svc.fire(99L)).isFalse();
    }

    @Test
    void fire_returnsTrueOnCasSuccess() {
        when(mapper.update(any(), any(Wrapper.class))).thenReturn(1);
        assertThat(svc.fire(99L)).isTrue();
    }
}
