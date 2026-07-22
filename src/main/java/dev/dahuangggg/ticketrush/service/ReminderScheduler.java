package dev.dahuangggg.ticketrush.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import dev.dahuangggg.ticketrush.entity.RushReminder;
import dev.dahuangggg.ticketrush.infrastructure.redis.RedisKeyRegistry;
import dev.dahuangggg.ticketrush.mapper.RushReminderMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
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
    private final DefaultRedisScript<Long> releaseLockScript;

    public ReminderScheduler(RushReminderMapper mapper,
                             RushReminderService service,
                             StringRedisTemplate redis,
                             Clock clock) {
        this.mapper = mapper;
        this.service = service;
        this.redis = redis;
        this.clock = clock;
        this.releaseLockScript = new DefaultRedisScript<>();
        this.releaseLockScript.setLocation(new ClassPathResource("lua/release_lock.lua"));
        this.releaseLockScript.setResultType(Long.class);
    }

    /** 主扫描：ZSet 取到期 → fire → ZREM。 */
    @Scheduled(fixedDelayString = "${ticket-rush.reminder.scan-interval-ms:10000}")
    public void scan() {
        String token = UUID.randomUUID().toString();
        String lockKey = RedisKeyRegistry.reminderScanLock();
        Boolean acquired = redis.opsForValue().setIfAbsent(lockKey, token, LOCK_TTL);
        if (!Boolean.TRUE.equals(acquired)) return;
        try {
            double now = nowEpochMillis();
            Set<String> due = redis.opsForZSet().rangeByScore(
                    RedisKeyRegistry.reminderDueZset(), 0d, now, 0L, BATCH);
            if (due == null || due.isEmpty()) return;
            for (String idStr : due) {
                Long id = Long.parseLong(idStr);
                try {
                    service.fire(id);
                } catch (Exception e) {
                    log.warn("fire reminder failed id={}", id, e);
                } finally {
                    redis.opsForZSet().remove(RedisKeyRegistry.reminderDueZset(), idStr);
                }
            }
        } finally {
            // Compare-and-delete: only release if we still own the lock (TTL may have expired
            // and another node acquired it). Without this check we could delete another node's lock.
            redis.execute(releaseLockScript, List.of(lockKey), token);
        }
    }

    /** 补偿：直接查 MySQL PENDING 且过期的，兜底 Redis 丢数据。 */
    @Scheduled(fixedDelayString = "${ticket-rush.reminder.compensate-interval-ms:60000}")
    public void compensate() {
        LocalDateTime now = LocalDateTime.now(clock);
        var pending = mapper.selectList(new LambdaQueryWrapper<RushReminder>()
                .eq(RushReminder::getStatus, RushReminder.STATUS_PENDING)
                .le(RushReminder::getTriggerAt, now)
                .last("LIMIT 200"));
        for (RushReminder r : pending) {
            try {
                if (service.fire(r.getId())) {
                    redis.opsForZSet().remove(RedisKeyRegistry.reminderDueZset(), String.valueOf(r.getId()));
                }
            } catch (Exception e) {
                log.warn("compensate fire failed id={}", r.getId(), e);
            }
        }
    }

    private double nowEpochMillis() {
        return LocalDateTime.now(clock).atZone(clock.getZone()).toInstant().toEpochMilli();
    }
}
