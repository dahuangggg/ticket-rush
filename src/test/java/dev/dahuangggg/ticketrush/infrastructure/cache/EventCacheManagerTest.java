package dev.dahuangggg.ticketrush.infrastructure.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Cache;
import dev.dahuangggg.ticketrush.dto.event.EventDetailDTO;
import dev.dahuangggg.ticketrush.infrastructure.observability.NoOpTicketRushMetrics;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class EventCacheManagerTest {

    @Test
    @SuppressWarnings("unchecked")
    void releasesNormalCacheLockWithTheAcquiredOwnershipToken() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(anyString())).thenReturn(null);
        when(values.setIfAbsent(eq("event:lock:42"), anyString(), any())).thenReturn(true);
        when(redis.execute(any(), eq(List.of("event:lock:42")), anyString())).thenReturn(1L);

        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        EventCacheManager manager = new EventCacheManager(
                redis,
                Caffeine.newBuilder().maximumSize(10).build(),
                Caffeine.newBuilder().maximumSize(10).build(),
                mapper,
                new NoOpTicketRushMetrics(),
                Clock.systemUTC(),
                true);
        EventDetailDTO expected = new EventDetailDTO(
                42L, "title", "artist", "city", "venue", LocalDateTime.now(),
                "cover", "description", 1, 0);

        EventDetailDTO actual = manager.getNormalEventDetail(42L, () -> expected);

        assertThat(actual).isEqualTo(expected);
        var tokenCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(values).setIfAbsent(eq("event:lock:42"), tokenCaptor.capture(), any());
        verify(redis).execute(any(), eq(List.of("event:lock:42")), eq(tokenCaptor.getValue()));
        verify(redis, never()).delete("event:lock:42");
        manager.shutdownRebuildExecutor();
    }

    @Test
    @SuppressWarnings("unchecked")
    void releaseFailureDoesNotMaskACompletedDatabaseLoad() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(anyString())).thenReturn(null);
        when(values.setIfAbsent(eq("event:lock:42"), anyString(), any())).thenReturn(true);
        when(redis.execute(any(), eq(List.of("event:lock:42")), anyString()))
                .thenThrow(new IllegalStateException("redis unavailable"));

        EventCacheManager manager = new EventCacheManager(
                redis,
                Caffeine.newBuilder().maximumSize(10).build(),
                Caffeine.newBuilder().maximumSize(10).build(),
                new ObjectMapper().registerModule(new JavaTimeModule()),
                new NoOpTicketRushMetrics(),
                Clock.systemUTC(),
                true);
        EventDetailDTO expected = new EventDetailDTO(
                42L, "title", "artist", "city", "venue", LocalDateTime.now(),
                "cover", "description", 1, 0);

        assertThat(manager.getNormalEventDetail(42L, () -> expected)).isEqualTo(expected);
        manager.shutdownRebuildExecutor();
    }

    @Test
    @SuppressWarnings("unchecked")
    void normalLocalEntryIsNotMistakenForAHotCacheHit() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(anyString())).thenReturn(null);
        when(values.setIfAbsent(eq("event:lock:42"), anyString(), any())).thenReturn(true);
        when(redis.execute(any(), eq(List.of("event:lock:42")), anyString())).thenReturn(1L);

        EventCacheManager manager = new EventCacheManager(
                redis,
                Caffeine.newBuilder().maximumSize(10).build(),
                Caffeine.newBuilder().maximumSize(10).build(),
                new ObjectMapper().registerModule(new JavaTimeModule()),
                new NoOpTicketRushMetrics(),
                Clock.systemUTC(),
                true);
        EventDetailDTO normal = new EventDetailDTO(
                42L, "normal", "artist", "city", "venue", LocalDateTime.now(),
                "cover", "description", 1, 0);

        assertThat(manager.getNormalEventDetail(42L, () -> normal)).isEqualTo(normal);
        assertThat(manager.getHotEventDetail(42L, () -> {
            throw new AssertionError("a missing hot key must not trigger rebuild");
        })).isNull();

        manager.shutdownRebuildExecutor();
    }

    @Test
    @SuppressWarnings("unchecked")
    void corruptNormalCacheFallsBackToDatabaseInsteadOfCreatingAFalseNotFound() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get("event:detail:42")).thenReturn("{broken-json", (String) null);
        when(values.setIfAbsent(eq("event:lock:42"), anyString(), any())).thenReturn(true);
        when(redis.execute(any(), eq(List.of("event:lock:42")), anyString())).thenReturn(1L);

        Cache<Long, EventDetailLocalValue> local = Caffeine.newBuilder().maximumSize(10).build();
        local.put(42L, EventDetailLocalValue.normal("{also-broken"));
        EventCacheManager manager = new EventCacheManager(
                redis,
                local,
                Caffeine.newBuilder().maximumSize(10).build(),
                new ObjectMapper().registerModule(new JavaTimeModule()),
                new NoOpTicketRushMetrics(),
                Clock.systemUTC(),
                true);
        EventDetailDTO expected = new EventDetailDTO(
                42L, "database", "artist", "city", "venue", LocalDateTime.now(),
                "cover", "description", 1, 0);

        assertThat(manager.getNormalEventDetail(42L, () -> expected)).isEqualTo(expected);
        verify(redis).delete("event:detail:42");
        manager.shutdownRebuildExecutor();
    }
}
