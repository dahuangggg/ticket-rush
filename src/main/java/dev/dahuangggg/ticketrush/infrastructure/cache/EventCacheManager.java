package dev.dahuangggg.ticketrush.infrastructure.cache;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import dev.dahuangggg.ticketrush.dto.event.EventDetailDTO;
import dev.dahuangggg.ticketrush.dto.event.EventDTO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

import org.springframework.beans.factory.annotation.Value;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

@Component
public class EventCacheManager {

    private static final Logger log = LoggerFactory.getLogger(EventCacheManager.class);

    /*
     * 异步重建执行器：热点活动缓存逻辑过期后，由此线程池异步重建，
     * 不阻塞正在进行的用户请求（返回旧数据，后台更新缓存）。
     */
    private static final ExecutorService REBUILD_EXECUTOR = Executors.newFixedThreadPool(4);

    private static final String DETAIL_KEY = "event:detail:";
    private static final String HOT_DETAIL_KEY = "event:detail:hot:";
    private static final String NULL_KEY = "event:null:";
    private static final String LOCK_KEY = "event:lock:";
    private static final String LIST_KEY = "event:list:";

    private static final Duration DETAIL_TTL_BASE = Duration.ofMinutes(30);
    private static final Duration DETAIL_TTL_JITTER = Duration.ofMinutes(5);
    private static final Duration NULL_TTL = Duration.ofMinutes(2);
    private static final Duration LOCK_TTL = Duration.ofSeconds(10);
    private static final Duration LIST_TTL_BASE = Duration.ofMinutes(10);
    private static final Duration LIST_TTL_JITTER = Duration.ofMinutes(3);
    private static final Duration LOGICAL_EXPIRE_DURATION = Duration.ofMinutes(30);

    private final StringRedisTemplate redisTemplate;
    private final Cache<Long, String> eventDetailLocalCache;
    private final Cache<String, String> eventListLocalCache;
    private final ObjectMapper objectMapper;

    // bench-db profile 将此值设为 false 以禁用 Redis，模拟纯 DB 场景
    private final boolean redisEnabled;

    public EventCacheManager(StringRedisTemplate redisTemplate,
                             Cache<Long, String> eventDetailLocalCache,
                             Cache<String, String> eventListLocalCache,
                             ObjectMapper objectMapper,
                             @Value("${ticket-rush.cache.redis-enabled:true}") boolean redisEnabled) {
        this.redisTemplate = redisTemplate;
        this.eventDetailLocalCache = eventDetailLocalCache;
        this.eventListLocalCache = eventListLocalCache;
        this.objectMapper = objectMapper;
        this.redisEnabled = redisEnabled;
    }

    // ========== 空值缓存 ==========

    /**
     * 写入空值标记，用于缓存穿透防护。
     * DB 确认活动不存在时调用，后续相同 ID 的查询直接命中此标记返回 404。
     */
    public void cacheNull(Long eventId) {
        if (!redisEnabled) return;
        redisTemplate.opsForValue().set(NULL_KEY + eventId, "", NULL_TTL);
    }

    public boolean isNull(Long eventId) {
        if (!redisEnabled) return false;
        return Boolean.TRUE.equals(redisTemplate.hasKey(NULL_KEY + eventId));
    }

    // ========== 普通活动详情（Cache-Aside + 互斥锁防击穿）==========

    /**
     * 获取普通活动详情，Cache-Aside 模式。
     *
     * 命中 Caffeine → 返回
     * 命中 Redis → 写入 Caffeine → 返回
     * 未命中 → 尝试获取互斥锁
     *   获锁成功 → 调用 dbLoader 查 DB → 写入 Redis 和 Caffeine → 返回
     *   获锁失败 → 短暂等待后重试（最多 3 次），避免大量请求同时重建
     *
     * 返回 null 表示 DB 中也不存在，由调用方缓存空值并抛出 404。
     */
    public EventDetailDTO getNormalEventDetail(Long eventId, Supplier<EventDetailDTO> dbLoader) {
        // 1. 查 Caffeine 本地缓存
        String localJson = eventDetailLocalCache.getIfPresent(eventId);
        if (localJson != null) {
            return deserialize(localJson, EventDetailDTO.class);
        }

        // 2. 查 Redis（bench-db profile 下跳过）
        if (redisEnabled) {
            String redisJson = redisTemplate.opsForValue().get(DETAIL_KEY + eventId);
            if (redisJson != null) {
                eventDetailLocalCache.put(eventId, redisJson);
                return deserialize(redisJson, EventDetailDTO.class);
            }
        }

        // 3. 缓存未命中，尝试互斥锁重建（最多重试 3 次）
        if (redisEnabled) {
            for (int i = 0; i < 3; i++) {
                Boolean locked = redisTemplate.opsForValue()
                        .setIfAbsent(LOCK_KEY + eventId, "1", LOCK_TTL);

                if (Boolean.TRUE.equals(locked)) {
                    try {
                        String doubleCheckJson = redisTemplate.opsForValue().get(DETAIL_KEY + eventId);
                        if (doubleCheckJson != null) {
                            eventDetailLocalCache.put(eventId, doubleCheckJson);
                            return deserialize(doubleCheckJson, EventDetailDTO.class);
                        }
                        EventDetailDTO dto = dbLoader.get();
                        if (dto != null) {
                            String json = serialize(dto);
                            long jitterSeconds = ThreadLocalRandom.current()
                                    .nextLong(0, DETAIL_TTL_JITTER.toSeconds());
                            redisTemplate.opsForValue().set(
                                    DETAIL_KEY + eventId, json,
                                    DETAIL_TTL_BASE.plusSeconds(jitterSeconds));
                            eventDetailLocalCache.put(eventId, json);
                        }
                        return dto;
                    } finally {
                        redisTemplate.delete(LOCK_KEY + eventId);
                    }
                }

                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }

                String retryJson = redisTemplate.opsForValue().get(DETAIL_KEY + eventId);
                if (retryJson != null) {
                    eventDetailLocalCache.put(eventId, retryJson);
                    return deserialize(retryJson, EventDetailDTO.class);
                }
            }
        }

        // Redis 禁用，或重试耗尽，直接查 DB
        return dbLoader.get();
    }

    // ========== 热点活动详情（逻辑过期 + 异步重建防击穿）==========

    /**
     * 获取热点活动详情，逻辑过期模式。
     *
     * 热点活动 Redis key 不设置真实 TTL，value 内嵌 expireAt 时间戳。
     * 读取逻辑：
     *   命中 Caffeine → 返回（Caffeine 有真实 TTL，过期会自动淘汰）
     *   命中 Redis：
     *     逻辑未过期 → 写入 Caffeine → 返回
     *     逻辑已过期 → 尝试获互斥锁
     *       获锁成功 → 异步重建缓存（新线程查 DB）→ 返回旧数据
     *       获锁失败 → 直接返回旧数据（其他线程正在重建）
     *   Redis 未命中 → 返回 null（冷启动场景，调用方应触发 warmUp）
     */
    public EventDetailDTO getHotEventDetail(Long eventId, Supplier<EventDetailDTO> dbLoader) {
        // 1. 查 Caffeine
        String localJson = eventDetailLocalCache.getIfPresent(eventId);
        if (localJson != null) {
            return deserialize(localJson, EventDetailDTO.class);
        }

        // 2. 查 Redis（bench-db profile 下跳过，直接穿透到 DB）
        if (!redisEnabled) {
            return null;
        }
        String redisJson = redisTemplate.opsForValue().get(HOT_DETAIL_KEY + eventId);
        if (redisJson == null) {
            return null;
        }

        LogicalExpireValue<EventDetailDTO> wrapper = deserializeLogical(redisJson, EventDetailDTO.class);
        if (wrapper == null) {
            return null;
        }

        EventDetailDTO data = wrapper.data();

        if (LocalDateTime.now().isBefore(wrapper.expireAt())) {
            // 逻辑未过期，写入 Caffeine 并返回
            eventDetailLocalCache.put(eventId, serialize(data));
            return data;
        }

        // 逻辑已过期，尝试异步重建
        Boolean locked = redisTemplate.opsForValue()
                .setIfAbsent(LOCK_KEY + "hot:" + eventId, "1", LOCK_TTL);

        if (Boolean.TRUE.equals(locked)) {
            // 获锁成功，异步重建，当前请求先返回旧数据
            REBUILD_EXECUTOR.submit(() -> {
                try {
                    EventDetailDTO fresh = dbLoader.get();
                    if (fresh != null) {
                        warmUp(eventId, fresh);
                    }
                } finally {
                    redisTemplate.delete(LOCK_KEY + "hot:" + eventId);
                }
            });
        }
        // 获锁失败或已提交重建任务，直接返回旧数据（可接受的短暂不一致）
        return data;
    }

    /**
     * 预热热点活动缓存。
     * 活动被标记为 is_hot=1 时，或动态检测到访问量超阈值时调用。
     * 写入逻辑过期格式，不设置真实 TTL（key 永不自动过期，靠逻辑过期控制刷新频率）。
     */
    public void warmUp(Long eventId, EventDetailDTO dto) {
        if (!redisEnabled) return;
        LocalDateTime expireAt = LocalDateTime.now().plus(LOGICAL_EXPIRE_DURATION);
        LogicalExpireValue<EventDetailDTO> wrapper = new LogicalExpireValue<>(dto, expireAt);
        redisTemplate.opsForValue().set(HOT_DETAIL_KEY + eventId, serialize(wrapper));
        eventDetailLocalCache.put(eventId, serialize(dto));
        log.info("Hot event cache warmed up for eventId={}", eventId);
    }

    // ========== 缓存删除（数据一致性）==========

    /**
     * 删除活动的所有缓存（普通 + 热点 + Caffeine + 空值标记）。
     * 活动信息更新时同步调用，失败时由 Kafka 消费者异步重试。
     */
    public void invalidate(Long eventId) {
        if (redisEnabled) {
            redisTemplate.delete(DETAIL_KEY + eventId);
            redisTemplate.delete(HOT_DETAIL_KEY + eventId);
            redisTemplate.delete(NULL_KEY + eventId);
        }
        eventDetailLocalCache.invalidate(eventId);
    }

    // ========== 列表缓存 ==========

    public List<EventDTO> getEventList(String cacheKey) {
        String localJson = eventListLocalCache.getIfPresent(cacheKey);
        if (localJson != null) {
            return deserializeList(localJson);
        }

        if (redisEnabled) {
            String redisJson = redisTemplate.opsForValue().get(LIST_KEY + cacheKey);
            if (redisJson != null) {
                eventListLocalCache.put(cacheKey, redisJson);
                return deserializeList(redisJson);
            }
        }

        return null;
    }

    public void cacheEventList(String cacheKey, List<EventDTO> list) {
        String json = serialize(list);
        if (redisEnabled) {
            long jitterSeconds = ThreadLocalRandom.current()
                    .nextLong(0, LIST_TTL_JITTER.toSeconds());
            redisTemplate.opsForValue().set(
                    LIST_KEY + cacheKey, json,
                    LIST_TTL_BASE.plusSeconds(jitterSeconds));
        }
        eventListLocalCache.put(cacheKey, json);
    }

    @PreDestroy
    void shutdownRebuildExecutor() {
        REBUILD_EXECUTOR.shutdown();
        log.info("EventCacheManager rebuild executor shutdown initiated");
    }

    // ========== 序列化工具 ==========

    private <T> String serialize(T obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("序列化失败", e);
        }
    }

    private <T> T deserialize(String json, Class<T> clazz) {
        try {
            return objectMapper.readValue(json, clazz);
        } catch (JsonProcessingException e) {
            log.warn("反序列化失败，json={}", json, e);
            return null;
        }
    }

    private List<EventDTO> deserializeList(String json) {
        try {
            return objectMapper.readValue(json,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, EventDTO.class));
        } catch (JsonProcessingException e) {
            log.warn("列表反序列化失败", e);
            return null;
        }
    }

    private <T> LogicalExpireValue<T> deserializeLogical(String json, Class<T> dataClass) {
        try {
            var type = objectMapper.getTypeFactory()
                    .constructParametricType(LogicalExpireValue.class, dataClass);
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException e) {
            log.warn("逻辑过期值反序列化失败", e);
            return null;
        }
    }
}
