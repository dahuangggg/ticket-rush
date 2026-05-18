package dev.dahuangggg.ticketrush.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import dev.dahuangggg.ticketrush.dto.reminder.ReminderDTO;
import dev.dahuangggg.ticketrush.dto.reminder.ReminderToolResult;
import dev.dahuangggg.ticketrush.dto.sku.TicketSkuDTO;
import dev.dahuangggg.ticketrush.entity.RushReminder;
import dev.dahuangggg.ticketrush.entity.TicketSku;
import dev.dahuangggg.ticketrush.exception.ReminderNotFoundException;
import dev.dahuangggg.ticketrush.exception.TicketSkuNotFoundException;
import dev.dahuangggg.ticketrush.infrastructure.redis.RedisKeyRegistry;
import dev.dahuangggg.ticketrush.mapper.RushReminderMapper;
import dev.dahuangggg.ticketrush.service.RushReminderService;
import dev.dahuangggg.ticketrush.service.TicketSkuService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class RushReminderServiceImpl implements RushReminderService {

    private static final Logger log = LoggerFactory.getLogger(RushReminderServiceImpl.class);
    private static final int DEFAULT_LEAD = 5;
    private static final int MIN_LEAD = 1;
    private static final int MAX_LEAD = 1440;

    private final RushReminderMapper mapper;
    private final TicketSkuService skuService;
    private final StringRedisTemplate redis;
    private final Clock clock;
    private final int maxPendingPerUser;

    public RushReminderServiceImpl(RushReminderMapper mapper,
                                   TicketSkuService skuService,
                                   StringRedisTemplate redis,
                                   Clock clock,
                                   @Value("${ticketrush.ai.reminder.max-pending-per-user:20}") int maxPendingPerUser) {
        this.mapper = mapper;
        this.skuService = skuService;
        this.redis = redis;
        this.clock = clock;
        this.maxPendingPerUser = maxPendingPerUser;
    }

    @Override
    @Transactional
    public ReminderToolResult setReminder(Long userId, Long skuId, Integer leadMinutes) {
        TicketSkuDTO sku;
        try {
            sku = skuService.getSkuDetail(skuId);
        } catch (TicketSkuNotFoundException e) {
            return ReminderToolResult.builder().ok(false).message("票档不存在").build();
        }

        LocalDateTime now = LocalDateTime.now(clock);
        if (sku.saleStartTime() == null || !sku.saleStartTime().isAfter(now)
                || (sku.status() != null && sku.status() == TicketSku.STATUS_ON_SALE)) {
            return ReminderToolResult.builder().ok(false).message("该票档已开售或已结束").build();
        }
        int lead = leadMinutes == null ? DEFAULT_LEAD
                : Math.max(MIN_LEAD, Math.min(MAX_LEAD, leadMinutes));
        LocalDateTime triggerAt = sku.saleStartTime().minusMinutes(lead);
        if (!triggerAt.isAfter(now)) {
            return ReminderToolResult.builder().ok(false)
                    .message("距离开抢时间太近，无法设置").build();
        }

        RushReminder existing = mapper.selectOne(new LambdaQueryWrapper<RushReminder>()
                .eq(RushReminder::getUserId, userId)
                .eq(RushReminder::getSkuId, skuId));
        if (existing == null) {
            Long pending = mapper.selectCount(new LambdaQueryWrapper<RushReminder>()
                    .eq(RushReminder::getUserId, userId)
                    .eq(RushReminder::getStatus, RushReminder.STATUS_PENDING));
            if (pending != null && pending >= maxPendingPerUser) {
                return ReminderToolResult.builder().ok(false)
                        .message("提醒数量已达上限 (" + maxPendingPerUser + ")").build();
            }
            RushReminder r = RushReminder.builder()
                    .userId(userId).skuId(skuId).eventId(sku.eventId())
                    .leadMinutes(lead).triggerAt(triggerAt)
                    .status(RushReminder.STATUS_PENDING).readFlag(0).build();
            mapper.insert(r);
            existing = r;
        } else {
            existing.setLeadMinutes(lead);
            existing.setTriggerAt(triggerAt);
            existing.setStatus(RushReminder.STATUS_PENDING);
            existing.setReadFlag(0);
            existing.setEventId(sku.eventId());
            mapper.updateById(existing);
        }

        redis.opsForZSet().add(RedisKeyRegistry.aiReminderZset(),
                String.valueOf(existing.getId()), toEpochMillis(triggerAt));

        return ReminderToolResult.builder().ok(true)
                .reminderId(existing.getId()).triggerAt(triggerAt)
                .message("已为你设置提醒：开抢前 " + lead + " 分钟（" + triggerAt + "）通知你").build();
    }

    @Override
    public List<ReminderDTO> list(Long userId, String filterStatus, boolean unreadOnly) {
        LambdaQueryWrapper<RushReminder> q = new LambdaQueryWrapper<RushReminder>()
                .eq(RushReminder::getUserId, userId)
                .orderByDesc(RushReminder::getId);
        if (filterStatus != null) q.eq(RushReminder::getStatus, filterStatus);
        if (unreadOnly) q.eq(RushReminder::getReadFlag, 0);
        return mapper.selectList(q).stream().map(this::toDto).collect(Collectors.toList());
    }

    @Override
    public void markRead(Long userId, Long reminderId) {
        int updated = mapper.update(null, new LambdaUpdateWrapper<RushReminder>()
                .eq(RushReminder::getId, reminderId)
                .eq(RushReminder::getUserId, userId)
                .set(RushReminder::getReadFlag, 1));
        if (updated == 0) throw new ReminderNotFoundException(reminderId);
    }

    @Override
    @Transactional
    public void cancel(Long userId, Long reminderId) {
        int updated = mapper.update(null, new LambdaUpdateWrapper<RushReminder>()
                .eq(RushReminder::getId, reminderId)
                .eq(RushReminder::getUserId, userId)
                .set(RushReminder::getStatus, RushReminder.STATUS_CANCELLED));
        if (updated == 0) throw new ReminderNotFoundException(reminderId);
        redis.opsForZSet().remove(RedisKeyRegistry.aiReminderZset(), String.valueOf(reminderId));
    }

    @Override
    public boolean fire(Long reminderId) {
        return mapper.update(null, new LambdaUpdateWrapper<RushReminder>()
                .eq(RushReminder::getId, reminderId)
                .eq(RushReminder::getStatus, RushReminder.STATUS_PENDING)
                .set(RushReminder::getStatus, RushReminder.STATUS_FIRED)) == 1;
    }

    private double toEpochMillis(LocalDateTime t) {
        return t.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    private ReminderDTO toDto(RushReminder r) {
        return ReminderDTO.builder()
                .id(r.getId()).skuId(r.getSkuId()).eventId(r.getEventId())
                .leadMinutes(r.getLeadMinutes())
                .triggerAt(r.getTriggerAt()).status(r.getStatus())
                .unread(r.getReadFlag() != null && r.getReadFlag() == 0)
                .createTime(r.getCreateTime()).build();
    }
}
