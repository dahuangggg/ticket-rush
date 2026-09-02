package dev.dahuangggg.ticketrush.infrastructure.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import dev.dahuangggg.ticketrush.dto.event.EventDetailDTO;
import dev.dahuangggg.ticketrush.infrastructure.mq.EventCacheInvalidationProducer;
import dev.dahuangggg.ticketrush.infrastructure.observability.NoOpTicketRushMetrics;
import dev.dahuangggg.ticketrush.mapper.EventMapper;
import dev.dahuangggg.ticketrush.service.BloomFilterService;
import dev.dahuangggg.ticketrush.service.impl.EventServiceImpl;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class EventServiceLocalCacheTest {

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void localHitReturnsBeforeBloomAndRedisNegativeCacheChecks(boolean hot) throws Exception {
        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        Cache<Long, EventDetailLocalValue> localCache = Caffeine.newBuilder().maximumSize(10).build();
        EventDetailDTO expected = new EventDetailDTO(
                42L, "title", "artist", "city", "venue", LocalDateTime.now(),
                "cover", "description", 1, 0);
        String json = objectMapper.writeValueAsString(expected);
        localCache.put(42L, hot
                ? EventDetailLocalValue.hot(json)
                : EventDetailLocalValue.normal(json));

        AtomicInteger redisNegativeChecks = new AtomicInteger();
        StringRedisTemplate redis = new StringRedisTemplate() {
            @Override
            public Boolean hasKey(String key) {
                redisNegativeChecks.incrementAndGet();
                throw new AssertionError("a local cache hit must not query Redis: " + key);
            }
        };
        EventCacheManager cacheManager = new EventCacheManager(
                redis,
                localCache,
                Caffeine.newBuilder().maximumSize(10).build(),
                objectMapper,
                new NoOpTicketRushMetrics(),
                Clock.systemUTC(),
                true);

        AtomicInteger bloomChecks = new AtomicInteger();
        BloomFilterService bloomFilter = new BloomFilterService() {
            @Override
            public boolean mightExist(Long eventId) {
                bloomChecks.incrementAndGet();
                return true;
            }

            @Override
            public void add(Long eventId) {
            }
        };
        EventMapper eventMapper = (EventMapper) Proxy.newProxyInstance(
                EventMapper.class.getClassLoader(),
                new Class<?>[]{EventMapper.class},
                (proxy, method, args) -> {
                    throw new AssertionError("a local cache hit must not query MySQL");
                });
        AtomicInteger trackedHot = new AtomicInteger(-1);
        EventServiceImpl service = new EventServiceImpl(
                eventMapper,
                cacheManager,
                new EventCacheInvalidationProducer(null, objectMapper),
                bloomFilter,
                (eventId, isHot, detail) -> trackedHot.set(isHot ? 1 : 0));

        try {
            assertThat(service.getEventDetail(42L)).isEqualTo(expected);
            assertThat(bloomChecks).hasValue(0);
            assertThat(redisNegativeChecks).hasValue(0);
            assertThat(trackedHot).hasValue(hot ? 1 : 0);
        } finally {
            cacheManager.shutdownRebuildExecutor();
        }
    }
}
