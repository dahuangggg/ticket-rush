package dev.dahuangggg.ticketrush.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import dev.dahuangggg.ticketrush.cache.EventCacheManager;
import dev.dahuangggg.ticketrush.dto.event.EventDetailDTO;
import dev.dahuangggg.ticketrush.dto.event.EventDTO;
import dev.dahuangggg.ticketrush.dto.event.EventListRequest;
import dev.dahuangggg.ticketrush.entity.Event;
import dev.dahuangggg.ticketrush.exception.EventNotFoundException;
import dev.dahuangggg.ticketrush.kafka.EventCacheInvalidationProducer;
import dev.dahuangggg.ticketrush.mapper.EventMapper;
import dev.dahuangggg.ticketrush.service.BloomFilterService;
import dev.dahuangggg.ticketrush.service.EventService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.List;

@Service
public class EventServiceImpl implements EventService {

    private static final Logger log = LoggerFactory.getLogger(EventServiceImpl.class);

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
     * BloomFilterService is conditional on ticket-rush.redisson.enabled=true.
     * When Redisson is disabled, no bean is registered — inject as optional
     * and treat null as "pass-through" (skip bloom filter check).
     */
    @Autowired(required = false)
    private BloomFilterService bloomFilterService;

    public EventServiceImpl(EventMapper eventMapper,
                            EventCacheManager cacheManager,
                            EventCacheInvalidationProducer cacheInvalidationProducer,
                            StringRedisTemplate redisTemplate) {
        this.eventMapper = eventMapper;
        this.cacheManager = cacheManager;
        this.cacheInvalidationProducer = cacheInvalidationProducer;
        this.redisTemplate = redisTemplate;
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
        //    bloomFilterService 为 null 时（Redisson 未启用）跳过此检查
        if (bloomFilterService != null && !bloomFilterService.mightExist(eventId)) {
            throw new EventNotFoundException(eventId);
        }

        // 2. 空值缓存（DB 已确认不存在的 ID）
        if (cacheManager.isNull(eventId)) {
            throw new EventNotFoundException(eventId);
        }

        // 3. 先从 DB 判断 isHot，决定缓存策略
        //    注意：isHot 可能在缓存中不存在（如首次访问），需查 DB 一次获取路由信息
        Event eventMeta = eventMapper.selectById(eventId);

        EventDetailDTO result;

        if (eventMeta != null && Integer.valueOf(1).equals(eventMeta.getIsHot())) {
            // 热点活动：逻辑过期缓存
            result = cacheManager.getHotEventDetail(eventId, () -> toDetailDTO(eventMeta));
            if (result == null) {
                // 冷启动（缓存未预热），立即预热并返回 DB 数据
                result = toDetailDTO(eventMeta);
                cacheManager.warmUp(eventId, result);
            }
        } else if (eventMeta != null) {
            // 普通活动：Cache-Aside + 互斥锁
            result = cacheManager.getNormalEventDetail(eventId, () -> toDetailDTO(eventMeta));
        } else {
            // DB 也不存在，缓存空值防止下次穿透
            cacheManager.cacheNull(eventId);
            throw new EventNotFoundException(eventId);
        }

        // 4. 异步计数，动态热点检测（不阻塞响应）
        trackAccessAsync(eventId, eventMeta);

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

    private void trackAccessAsync(Long eventId, Event event) {
        // 访问量统计不能影响主流程，异步执行（Java 17 兼容：使用普通守护线程）
        Thread t = new Thread(() -> {
            try {
                String key = ACCESS_COUNT_KEY + eventId;
                Long count = redisTemplate.opsForValue().increment(key);
                if (count != null && count == 1) {
                    redisTemplate.expire(key, ACCESS_COUNT_WINDOW);
                }
                if (count != null && count >= HOT_DETECT_THRESHOLD
                        && event != null && !Integer.valueOf(1).equals(event.getIsHot())) {
                    log.warn("Dynamic hot event detected: eventId={}, accessCount={} in 1min. "
                            + "Consider marking is_hot=1 in admin console.", eventId, count);
                    // 自动触发预热，作为运营漏标的兜底
                    cacheManager.warmUp(eventId, toDetailDTO(event));
                }
            } catch (Exception e) {
                log.warn("Access tracking failed for eventId={}", eventId, e);
            }
        });
        t.setDaemon(true);
        t.start();
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
