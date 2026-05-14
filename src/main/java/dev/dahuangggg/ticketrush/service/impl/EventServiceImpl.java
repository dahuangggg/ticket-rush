package dev.dahuangggg.ticketrush.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import dev.dahuangggg.ticketrush.infrastructure.cache.EventCacheManager;
import dev.dahuangggg.ticketrush.dto.event.EventDetailDTO;
import dev.dahuangggg.ticketrush.dto.event.EventDTO;
import dev.dahuangggg.ticketrush.dto.event.EventListRequest;
import dev.dahuangggg.ticketrush.entity.Event;
import dev.dahuangggg.ticketrush.exception.EventNotFoundException;
import dev.dahuangggg.ticketrush.infrastructure.mq.EventCacheInvalidationProducer;
import dev.dahuangggg.ticketrush.mapper.EventMapper;
import dev.dahuangggg.ticketrush.service.BloomFilterService;
import dev.dahuangggg.ticketrush.service.EventService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class EventServiceImpl implements EventService {

    private static final Logger log = LoggerFactory.getLogger(EventServiceImpl.class);

    private static final ExecutorService ACCESS_TRACKER_EXECUTOR =
            Executors.newFixedThreadPool(2, r -> {
                Thread t = new Thread(r, "access-tracker");
                t.setDaemon(true);
                return t;
            });

    /*
     * 动态热点检测阈值：1 分钟内访问量超过此值，触发缓存预热并告警。
     * 仅作为运营漏标后的补救手段，不是第一道防线。
     */
    private static final long HOT_DETECT_THRESHOLD = 1000;
    private static final String ACCESS_COUNT_KEY = "event:access:count:";
    private static final Duration ACCESS_COUNT_WINDOW = Duration.ofMinutes(1);

    private final EventMapper eventMapper;
    private final EventCacheManager cacheManager;
    private final EventCacheInvalidationProducer cacheInvalidationProducer;
    private final StringRedisTemplate redisTemplate;

    /*
     * BloomFilterService is always present: either BloomFilterServiceImpl
     * (when Redisson is enabled) or NoOpBloomFilterService (when disabled).
     */
    private final BloomFilterService bloomFilterService;

    public EventServiceImpl(EventMapper eventMapper,
                            EventCacheManager cacheManager,
                            EventCacheInvalidationProducer cacheInvalidationProducer,
                            StringRedisTemplate redisTemplate,
                            BloomFilterService bloomFilterService) {
        this.eventMapper = eventMapper;
        this.cacheManager = cacheManager;
        this.cacheInvalidationProducer = cacheInvalidationProducer;
        this.redisTemplate = redisTemplate;
        this.bloomFilterService = bloomFilterService;
    }

    /**
     * 查询活动列表。
     *
     * 缓存策略：
     * - city + date 组合作为缓存 key，命中直接返回。
     * - keyword 不参与缓存 key，每次透传到 DB（模糊搜索结果组合太多，不适合缓存）。
     * - 未命中时查 DB，写入缓存（TTL 加随机抖动防雪崩）。
     *
     * 热点活动通过 is_hot 字段在 DB 层 ORDER BY is_hot DESC 置顶。
     */
    @Override
    public List<EventDTO> listEvents(EventListRequest request) {
        String cacheKey = buildListCacheKey(request);

        // 有 keyword 时不走缓存（组合太多）
        if (!StringUtils.hasText(request.keyword())) {
            List<EventDTO> cached = cacheManager.getEventList(cacheKey);
            if (cached != null) {
                return cached;
            }
        }

        List<EventDTO> result = queryEventListFromDb(request);

        if (!StringUtils.hasText(request.keyword())) {
            cacheManager.cacheEventList(cacheKey, result);
        }

        return result;
    }

    /**
     * 查询活动详情，完整缓存策略。
     *
     * 执行顺序：
     * 1. 布隆过滤器：一定不存在则直接 404，不查缓存和 DB。
     * 2. 空值缓存：Redis 有空值标记则直接 404（DB 之前已确认不存在）。
     * 3. 根据 is_hot 选择缓存策略：
     *    - 热点活动：逻辑过期，未命中时触发预热（冷启动兜底）。
     *    - 普通活动：Cache-Aside + 互斥锁。
     * 4. 异步记录访问量，超阈值时触发动态预热告警。
     */
    @Override
    public EventDetailDTO getEventDetail(Long eventId) {
        // 1. 布隆过滤器拦截（一定不存在，无需查 Redis 和 DB）
        if (!bloomFilterService.mightExist(eventId)) {
            throw new EventNotFoundException(eventId);
        }

        // 2. 空值缓存（DB 已确认不存在的 ID）
        if (cacheManager.isNull(eventId)) {
            throw new EventNotFoundException(eventId);
        }

        // 使用原子引用捕获 DB 查询结果，用于后续异步热点检测
        AtomicReference<Event> loadedEvent = new AtomicReference<>();

        // 3. 优先尝试热点活动缓存（不提前查 DB）
        //    热点活动命中率极高，无需每次查 DB 确认 isHot
        EventDetailDTO result = cacheManager.getHotEventDetail(eventId, () -> {
            // dbLoader 仅在热点缓存逻辑过期、异步重建时调用
            Event event = eventMapper.selectById(eventId);
            loadedEvent.set(event);  // 捕获 DB 查询结果
            return event != null ? toDetailDTO(event) : null;
        });

        if (result != null) {
            // 命中热点缓存（可能是旧数据，等待异步重建），直接返回
            trackAccessAsync(eventId, loadedEvent.get());
            return result;
        }

        // 4. 热点缓存未命中（普通活动，或热点活动冷启动）
        //    走 Cache-Aside + 互斥锁路径，dbLoader 在缓存未命中时才调 DB
        result = cacheManager.getNormalEventDetail(eventId, () -> {
            Event event = eventMapper.selectById(eventId);
            loadedEvent.set(event);  // 捕获 DB 查询结果
            if (event == null) {
                return null;
            }
            // 若发现是热点活动（冷启动场景），立即预热热点缓存
            if (Integer.valueOf(1).equals(event.getIsHot())) {
                EventDetailDTO dto = toDetailDTO(event);
                cacheManager.warmUp(eventId, dto);
                return dto;
            }
            return toDetailDTO(event);
        });

        if (result == null) {
            // DB 确认不存在，缓存空值防止下次穿透
            cacheManager.cacheNull(eventId);
            throw new EventNotFoundException(eventId);
        }

        trackAccessAsync(eventId, loadedEvent.get());
        return result;
    }

    /**
     * 更新活动后同步删除缓存，失败则发送 Kafka 消息异步兜底。
     *
     * 调用方（管理后台服务）在更新 DB 后调用此方法。
     * 活动状态变更（上下架）时必须调用，不能依赖 TTL 自然过期。
     */
    public void invalidateCache(Long eventId) {
        try {
            cacheManager.invalidate(eventId);
        } catch (Exception e) {
            log.error("Sync cache invalidation failed for eventId={}, sending to Kafka", eventId, e);
            cacheInvalidationProducer.send(eventId);
        }
    }

    // ========== 私有方法 ==========

    private List<EventDTO> queryEventListFromDb(EventListRequest request) {
        LambdaQueryWrapper<Event> wrapper = new LambdaQueryWrapper<Event>()
                .eq(Event::getStatus, 1)  // 只查售卖中的活动
                .eq(StringUtils.hasText(request.city()), Event::getCity, request.city())
                .like(StringUtils.hasText(request.keyword()), Event::getTitle, request.keyword())
                .ge(request.date() != null, Event::getEventTime,
                        request.date() != null ? request.date().atStartOfDay() : null)
                .lt(request.date() != null, Event::getEventTime,
                        request.date() != null ? request.date().plusDays(1).atStartOfDay() : null)
                .orderByDesc(Event::getIsHot)   // 热点活动置顶
                .orderByAsc(Event::getEventTime);

        return eventMapper.selectList(wrapper).stream()
                .map(this::toDTO)
                .toList();
    }

    @jakarta.annotation.PreDestroy
    void shutdownAccessTracker() {
        ACCESS_TRACKER_EXECUTOR.shutdown();
    }

    private void trackAccessAsync(Long eventId, Event event) {
        // 访问量统计不能影响主流程，使用共享有界线程池异步执行
        ACCESS_TRACKER_EXECUTOR.submit(() -> {
            try {
                String key = ACCESS_COUNT_KEY + eventId;
                Long count = redisTemplate.opsForValue().increment(key);
                if (count != null && count == 1L) {
                    redisTemplate.expire(key, ACCESS_COUNT_WINDOW);
                }
                if (count != null && count >= HOT_DETECT_THRESHOLD
                        && event != null && !Integer.valueOf(1).equals(event.getIsHot())) {
                    log.warn("Dynamic hot event detected: eventId={}, accessCount={} in 1min. "
                            + "Consider marking is_hot=1 in admin console.", eventId, count);
                    // 自动触发预热，作为运营漏标的兜底
                    cacheManager.warmUp(eventId, toDetailDTO(event));
                }
                // event == null means we arrived via the hot-cache path; warm-up already handled
            } catch (Exception e) {
                log.warn("Access tracking failed for eventId={}", eventId, e);
            }
        });
    }

    private String buildListCacheKey(EventListRequest request) {
        return (request.city() != null ? request.city() : "") + ":"
                + (request.date() != null ? request.date().toString() : "");
    }

    private EventDTO toDTO(Event event) {
        return new EventDTO(event.getId(), event.getTitle(), event.getArtist(),
                event.getCity(), event.getVenue(), event.getEventTime(),
                event.getCoverUrl(), event.getStatus(), event.getIsHot());
    }

    private EventDetailDTO toDetailDTO(Event event) {
        return new EventDetailDTO(event.getId(), event.getTitle(), event.getArtist(),
                event.getCity(), event.getVenue(), event.getEventTime(),
                event.getCoverUrl(), event.getDescription(),
                event.getStatus(), event.getIsHot());
    }
}
