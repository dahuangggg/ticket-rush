package dev.dahuangggg.ticketrush.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import dev.dahuangggg.ticketrush.entity.RushReminder;
import dev.dahuangggg.ticketrush.infrastructure.redis.RedisKeyRegistry;
import dev.dahuangggg.ticketrush.mapper.RushReminderMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Set;
import java.util.UUID;

@Component
public class ReminderScheduler {

    private static final Logger log = LoggerFactory.getLogger(ReminderScheduler.class);
    private static final Duration LOCK_TTL = Duration.ofSeconds(30);
    private static final int BATCH = 100;

    private final RushReminderMapper mapper;
    private final RushReminderService service;
    private final StringRedisTemplate redis;
    private final Clock clock;

    public ReminderScheduler(RushReminderMapper mapper,
                             RushReminderService service,
                             StringRedisTemplate redis,
                             Clock clock) {
        this.mapper = mapper;
        this.service = service;
        this.redis = redis;
        this.clock = clock;
    }

    /** 主扫描：ZSet 取到期 → fire → ZREM。 */
    @Scheduled(fixedDelayString = "${ticketrush.ai.reminder.scan-interval-ms:10000}")
    public void scan() {
        String token = UUID.randomUUID().toString();
        Boolean acquired = redis.opsForValue()
                .setIfAbsent(RedisKeyRegistry.aiReminderLock(), token, LOCK_TTL);
        if (!Boolean.TRUE.equals(acquired)) return;
        try {
            double now = nowEpochMillis();
            Set<String> due = redis.opsForZSet().rangeByScore(
                    RedisKeyRegistry.aiReminderZset(), 0d, now, 0L, BATCH);
            if (due == null || due.isEmpty()) return;
            for (String idStr : due) {
                Long id = Long.parseLong(idStr);
                try {
                    service.fire(id);
                } catch (Exception e) {
                    log.warn("fire reminder failed id={}", id, e);
                } finally {
                    redis.opsForZSet().remove(RedisKeyRegistry.aiReminderZset(), idStr);
                }
            }
        } finally {
            redis.delete(RedisKeyRegistry.aiReminderLock());
        }
    }

    /** 补偿：直接查 MySQL PENDING 且过期的，兜底 Redis 丢数据。 */
    @Scheduled(fixedDelayString = "${ticketrush.ai.reminder.compensate-interval-ms:60000}")
    public void compensate() {
        LocalDateTime now = LocalDateTime.now(clock);
        var pending = mapper.selectList(new LambdaQueryWrapper<RushReminder>()
                .eq(RushReminder::getStatus, RushReminder.STATUS_PENDING)
                .le(RushReminder::getTriggerAt, now)
                .last("LIMIT 200"));
        for (RushReminder r : pending) {
            try {
                if (service.fire(r.getId())) {
                    redis.opsForZSet().remove(RedisKeyRegistry.aiReminderZset(), String.valueOf(r.getId()));
                }
            } catch (Exception e) {
                log.warn("compensate fire failed id={}", r.getId(), e);
            }
        }
    }

    private double nowEpochMillis() {
        return LocalDateTime.now(clock).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }
}
