package dev.dahuangggg.ticketrush.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.toolkit.support.SFunction;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import dev.dahuangggg.ticketrush.entity.RushReminder;
import dev.dahuangggg.ticketrush.mapper.RushReminderMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.ZSetOperations;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SuppressWarnings({"unchecked", "rawtypes"})
class ReminderSchedulerTest {

    @BeforeAll
    static void initMpLambdaCache() {
        // MP needs an init for LambdaQueryWrapper to find the entity table mapping in unit tests.
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                RushReminder.class);
    }

    private RushReminderMapper mapper;
    private RushReminderService service;
    private StringRedisTemplate redis;
    private ValueOperations<String, String> ops;
    private ZSetOperations<String, String> zset;
    private ReminderScheduler scheduler;
    private Clock clock;

    @BeforeEach
    void setUp() {
        mapper = mock(RushReminderMapper.class);
        service = mock(RushReminderService.class);
        redis = mock(StringRedisTemplate.class);
        ops = mock(ValueOperations.class);
        zset = mock(ZSetOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        when(redis.opsForZSet()).thenReturn(zset);
        clock = Clock.fixed(Instant.parse("2026-05-18T10:00:00Z"), ZoneId.of("UTC"));
        scheduler = new ReminderScheduler(mapper, service, redis, clock);
    }

    @Test
    void scan_skipsWhenLockHeldByOther() {
        when(ops.setIfAbsent(eq("ai:reminder:lock"), anyString(), any(Duration.class))).thenReturn(false);
        scheduler.scan();
        verify(zset, never()).rangeByScore(anyString(), anyDouble(), anyDouble(), anyLong(), anyLong());
        verify(service, never()).fire(anyLong());
    }

    @Test
    void scan_firesDueReminders() {
        when(ops.setIfAbsent(eq("ai:reminder:lock"), anyString(), any(Duration.class))).thenReturn(true);
        when(zset.rangeByScore(eq("ai:reminder:zset"), eq(0d), anyDouble(), eq(0L), eq(100L)))
                .thenReturn(Set.of("10", "11"));
        when(service.fire(10L)).thenReturn(true);
        when(service.fire(11L)).thenReturn(false); // already fired by another node

        scheduler.scan();

        verify(service).fire(10L);
        verify(service).fire(11L);
        verify(zset).remove("ai:reminder:zset", "10");
        verify(zset).remove("ai:reminder:zset", "11");
    }

    @Test
    void compensate_firesOrphanedPendingFromDb() {
        RushReminder due = RushReminder.builder().id(55L)
                .status("PENDING").triggerAt(LocalDateTime.now(clock).minusMinutes(2)).build();
        when(mapper.selectList(any(Wrapper.class))).thenReturn(List.of(due));
        when(service.fire(55L)).thenReturn(true);

        scheduler.compensate();

        verify(service).fire(55L);
        verify(zset).remove("ai:reminder:zset", "55");
    }
}
