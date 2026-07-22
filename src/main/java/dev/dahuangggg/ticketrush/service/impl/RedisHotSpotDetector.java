package dev.dahuangggg.ticketrush.service.impl;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.RemovalCause;
import dev.dahuangggg.ticketrush.dto.event.EventDetailDTO;
import dev.dahuangggg.ticketrush.infrastructure.cache.EventCacheManager;
import dev.dahuangggg.ticketrush.infrastructure.observability.TicketRushMetrics;
import dev.dahuangggg.ticketrush.infrastructure.redis.RedisKeyRegistry;
import dev.dahuangggg.ticketrush.service.HotSpotDetector;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;

/**
 * Bounded, aggregating Adapter for dynamic hotspot detection.
 *
 * <p>The request path performs only a local counter increment. A single scheduled task batches the
 * deltas into Redis, replacing the previous one-task-per-request unbounded executor.</p>
 */
@Service
public class RedisHotSpotDetector implements HotSpotDetector {

    private static final Logger log = LoggerFactory.getLogger(RedisHotSpotDetector.class);

    private final StringRedisTemplate redisTemplate;
    private final EventCacheManager cacheManager;
    private final TicketRushMetrics metrics;
    private final boolean enabled;
    private final long hotDetectThreshold;
    private final int sampleEvery;
    private final Duration accessCountWindow;
    private final Cache<Long, AccessSample> samples;
    private final ScheduledExecutorService flushExecutor;
    private final AtomicBoolean flushing = new AtomicBoolean();

    public RedisHotSpotDetector(
            StringRedisTemplate redisTemplate,
            EventCacheManager cacheManager,
            TicketRushMetrics metrics,
            @Value("${ticket-rush.cache.redis-enabled:true}") boolean enabled,
            @Value("${ticket-rush.cache.hot-detect-threshold:1000}") long hotDetectThreshold,
            @Value("${ticket-rush.cache.access-count-window-seconds:60}") long accessCountWindowSeconds,
            @Value("${ticket-rush.cache.hot-detect-sample-every:10}") int sampleEvery,
            @Value("${ticket-rush.cache.hot-detect-max-tracked-events:10000}") long maxTrackedEvents,
            @Value("${ticket-rush.cache.hot-detect-flush-interval-ms:1000}") long flushIntervalMillis) {
        this.redisTemplate = redisTemplate;
        this.cacheManager = cacheManager;
        this.metrics = metrics;
        this.enabled = enabled;
        if (hotDetectThreshold < 1 || accessCountWindowSeconds < 1
                || maxTrackedEvents < 1 || flushIntervalMillis < 1) {
            throw new IllegalArgumentException(
                    "hotspot threshold, window, capacity and flush interval must all be positive");
        }
        this.hotDetectThreshold = hotDetectThreshold;
        if (sampleEvery < 1) {
            throw new IllegalArgumentException("hot-detect-sample-every must be at least 1");
        }
        this.sampleEvery = sampleEvery;
        this.accessCountWindow = Duration.ofSeconds(accessCountWindowSeconds);
        this.samples = Caffeine.<Long, AccessSample>newBuilder()
                .maximumSize(maxTrackedEvents)
                .expireAfterAccess(accessCountWindow.multipliedBy(2))
                .removalListener((Long eventId, AccessSample sample, RemovalCause cause) -> {
                    if (cause.wasEvicted() && sample != null && sample.count.sum() > 0) {
                        metrics.recordHotSpotSignal(TicketRushMetrics.HotSpotSignalOutcome.DROPPED);
                    }
                })
                .build();
        this.flushExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "hotspot-counter-flush");
            thread.setDaemon(true);
            return thread;
        });
        if (enabled) {
            this.flushExecutor.scheduleWithFixedDelay(
                    this::flushSafely,
                    flushIntervalMillis,
                    flushIntervalMillis,
                    TimeUnit.MILLISECONDS);
        }
    }

    @Override
    public void trackAccess(Long eventId, boolean isHot, EventDetailDTO detail) {
        // Already-hot events do not need dynamic detection. This also keeps the hottest read path
        // free from Redis accounting work.
        if (!enabled || isHot || detail == null) {
            return;
        }
        if (sampleEvery > 1 && ThreadLocalRandom.current().nextInt(sampleEvery) != 0) {
            return;
        }
        AccessSample sample = samples.get(eventId, ignored -> new AccessSample(detail));
        sample.detail.set(detail);
        // Scale sampled observations back to an estimated request count. The detector is a
        // promotion hint, not a billing counter; avoiding per-request Redis I/O is the priority.
        sample.count.add(sampleEvery);
        metrics.recordHotSpotSignal(TicketRushMetrics.HotSpotSignalOutcome.AGGREGATED);
    }

    /** Visible to package tests so aggregation can be verified without sleeping. */
    void flushNow() {
        if (!flushing.compareAndSet(false, true)) {
            return;
        }
        try {
            samples.asMap().forEach(this::flushSample);
            samples.cleanUp();
        } finally {
            flushing.set(false);
        }
    }

    private void flushSample(Long eventId, AccessSample sample) {
        long delta = sample.count.sumThenReset();
        if (delta == 0) {
            return;
        }

        try {
            String key = RedisKeyRegistry.eventAccessCountKey(eventId);
            Long count = redisTemplate.opsForValue().increment(key, delta);
            if (count != null && count == delta) {
                redisTemplate.expire(key, accessCountWindow);
            }
            if (count != null && count >= hotDetectThreshold) {
                cacheManager.warmUp(eventId, sample.detail.get());
                samples.invalidate(eventId);
                metrics.recordHotSpotSignal(TicketRushMetrics.HotSpotSignalOutcome.PROMOTED);
                log.warn("Dynamic hot event detected: eventId={}, accessCount={} in {}s",
                        eventId, count, accessCountWindow.toSeconds());
            }
        } catch (RuntimeException exception) {
            // Preserve the delta for the next flush instead of silently under-counting on a
            // transient Redis outage.
            sample.count.add(delta);
            metrics.recordHotSpotSignal(TicketRushMetrics.HotSpotSignalOutcome.FLUSH_FAILED);
            log.warn("Access counter flush failed for eventId={}", eventId, exception);
        }
    }

    private void flushSafely() {
        try {
            flushNow();
        } catch (RuntimeException exception) {
            // Keep the scheduler alive even if an unexpected bug escapes an individual sample.
            log.error("Hotspot counter flush failed", exception);
        }
    }

    @PreDestroy
    void shutdown() {
        flushExecutor.shutdown();
        flushSafely();
    }

    private static final class AccessSample {
        private final LongAdder count = new LongAdder();
        private final AtomicReference<EventDetailDTO> detail;

        private AccessSample(EventDetailDTO detail) {
            this.detail = new AtomicReference<>(detail);
        }
    }
}
