package dev.dahuangggg.ticketrush.service.impl;

import dev.dahuangggg.ticketrush.dto.event.EventDetailDTO;
import dev.dahuangggg.ticketrush.infrastructure.cache.EventCacheManager;
import dev.dahuangggg.ticketrush.infrastructure.redis.RedisKeyRegistry;
import dev.dahuangggg.ticketrush.service.HotSpotDetector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Service
public class RedisHotSpotDetector implements HotSpotDetector {

    private static final Logger log = LoggerFactory.getLogger(RedisHotSpotDetector.class);

    private final StringRedisTemplate redisTemplate;
    private final EventCacheManager cacheManager;
    private final long hotDetectThreshold;
    private final Duration accessCountWindow;
    private final ExecutorService executor;

    public RedisHotSpotDetector(StringRedisTemplate redisTemplate,
                                EventCacheManager cacheManager,
                                @Value("${ticket-rush.cache.hot-detect-threshold:1000}") long hotDetectThreshold,
                                @Value("${ticket-rush.cache.access-count-window-seconds:60}") long accessCountWindowSeconds) {
        this.redisTemplate = redisTemplate;
        this.cacheManager = cacheManager;
        this.hotDetectThreshold = hotDetectThreshold;
        this.accessCountWindow = Duration.ofSeconds(accessCountWindowSeconds);
        this.executor = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "access-tracker");
            t.setDaemon(true);
            return t;
        });
    }

    @Override
    public void trackAccessAsync(Long eventId, boolean isHot, EventDetailDTO detail) {
        executor.submit(() -> {
            try {
                String key = RedisKeyRegistry.eventAccessCountKey(eventId);
                Long count = redisTemplate.opsForValue().increment(key);
                if (count != null && count == 1L) {
                    redisTemplate.expire(key, accessCountWindow);
                }
                if (count != null && count >= hotDetectThreshold && !isHot && detail != null) {
                    log.warn("Dynamic hot event detected: eventId={}, accessCount={} in {}s. "
                            + "Consider marking is_hot=1 in admin console.", eventId, count, accessCountWindow.toSeconds());
                    cacheManager.warmUp(eventId, detail);
                }
            } catch (Exception e) {
                log.warn("Access tracking failed for eventId={}", eventId, e);
            }
        });
    }

    @PreDestroy
    void shutdown() {
        executor.shutdown();
    }
}
