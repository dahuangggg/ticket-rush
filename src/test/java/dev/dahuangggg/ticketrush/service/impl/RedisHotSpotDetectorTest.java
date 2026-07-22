package dev.dahuangggg.ticketrush.service.impl;

import dev.dahuangggg.ticketrush.dto.event.EventDetailDTO;
import dev.dahuangggg.ticketrush.infrastructure.cache.EventCacheManager;
import dev.dahuangggg.ticketrush.infrastructure.observability.NoOpTicketRushMetrics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.LocalDateTime;

import static org.mockito.Mockito.*;

class RedisHotSpotDetectorTest {

    private StringRedisTemplate redis;
    private ValueOperations<String, String> values;
    private EventCacheManager cacheManager;
    private RedisHotSpotDetector detector;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        values = mock(ValueOperations.class);
        cacheManager = mock(EventCacheManager.class);
        when(redis.opsForValue()).thenReturn(values);
        detector = new RedisHotSpotDetector(
                redis,
                cacheManager,
                new NoOpTicketRushMetrics(),
                true,
                100,
                60,
                1,
                1000,
                60_000);
    }

    @AfterEach
    void tearDown() {
        detector.shutdown();
    }

    @Test
    void aggregatesManyRequestSignalsIntoOneRedisIncrement() {
        EventDetailDTO detail = detail(42L);
        when(values.increment("event:access:count:42", 3L)).thenReturn(3L);

        detector.trackAccess(42L, false, detail);
        detector.trackAccess(42L, false, detail);
        detector.trackAccess(42L, false, detail);
        detector.flushNow();

        verify(values).increment("event:access:count:42", 3L);
        verifyNoInteractions(cacheManager);
    }

    @Test
    void restoresDeltaWhenRedisFlushFails() {
        EventDetailDTO detail = detail(42L);
        when(values.increment("event:access:count:42", 2L))
                .thenThrow(new IllegalStateException("redis unavailable"))
                .thenReturn(2L);

        detector.trackAccess(42L, false, detail);
        detector.trackAccess(42L, false, detail);
        detector.flushNow();
        detector.flushNow();

        verify(values, times(2)).increment("event:access:count:42", 2L);
    }

    @Test
    void skipsSignalsForEventsAlreadyMarkedHot() {
        detector.trackAccess(42L, true, detail(42L));
        detector.flushNow();

        verifyNoInteractions(values, cacheManager);
    }

    @Test
    void promotesEventAfterTheAggregatedWindowCrossesThreshold() {
        RedisHotSpotDetector thresholdDetector = new RedisHotSpotDetector(
                redis,
                cacheManager,
                new NoOpTicketRushMetrics(),
                true,
                3,
                60,
                1,
                1000,
                60_000);
        EventDetailDTO detail = detail(43L);
        when(values.increment("event:access:count:43", 3L)).thenReturn(3L);

        thresholdDetector.trackAccess(43L, false, detail);
        thresholdDetector.trackAccess(43L, false, detail);
        thresholdDetector.trackAccess(43L, false, detail);
        thresholdDetector.flushNow();

        verify(cacheManager).warmUp(43L, detail);
        thresholdDetector.shutdown();
    }

    private static EventDetailDTO detail(long id) {
        return new EventDetailDTO(
                id, "title", "artist", "city", "venue", LocalDateTime.now(),
                "cover", "description", 1, 0);
    }
}
