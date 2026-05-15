package dev.dahuangggg.ticketrush.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import dev.dahuangggg.ticketrush.entity.Event;
import dev.dahuangggg.ticketrush.mapper.EventMapper;
import dev.dahuangggg.ticketrush.infrastructure.redis.RedisKeyRegistry;
import dev.dahuangggg.ticketrush.service.BloomFilterService;
import jakarta.annotation.PostConstruct;
import org.redisson.api.RBloomFilter;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@ConditionalOnProperty(name = "ticket-rush.redisson.enabled", havingValue = "true")
public class BloomFilterServiceImpl implements BloomFilterService {

    private static final Logger log = LoggerFactory.getLogger(BloomFilterServiceImpl.class);

    /*
     * 布隆过滤器参数：
     * - 预期元素数量 10000（活动数量上限估算）
     * - 误判率 0.01（1%）：100 个不存在的 ID 中最多 1 个被误判为存在，
     *   误判后仍会查 DB，再由空值缓存兜底，不影响正确性，只有轻微性能损耗。
     *
     * Redisson RBloomFilter 底层基于 Redis BitMap，支持分布式多实例共享，
     * 不同于 JVM 内存中的 Bloom Filter，多实例部署时状态一致。
     */
    private static final long EXPECTED_INSERTIONS = 10_000;
    private static final double FALSE_POSITIVE_RATE = 0.01;

    private final RBloomFilter<Long> bloomFilter;
    private final EventMapper eventMapper;

    public BloomFilterServiceImpl(RedissonClient redissonClient, EventMapper eventMapper) {
        this.bloomFilter = redissonClient.getBloomFilter(RedisKeyRegistry.eventBloomFilterKey());
        this.bloomFilter.tryInit(EXPECTED_INSERTIONS, FALSE_POSITIVE_RATE);
        this.eventMapper = eventMapper;
    }

    /**
     * 服务启动时将数据库中所有合法 eventId 加载到布隆过滤器。
     *
     * 只加载未删除的活动 ID，软删除的活动不在过滤器中。
     * 注意：deleted = 0 的过滤由 MyBatis-Plus 的 @TableLogic 自动处理。
     */
    @PostConstruct
    void init() {
        List<Long> eventIds = eventMapper.selectList(
                new LambdaQueryWrapper<Event>().select(Event::getId)
        ).stream().map(Event::getId).toList();

        eventIds.forEach(bloomFilter::add);
        log.info("Bloom filter initialized with {} event IDs", eventIds.size());
    }

    @Override
    public boolean mightExist(Long eventId) {
        return bloomFilter.contains(eventId);
    }

    @Override
    public void add(Long eventId) {
        bloomFilter.add(eventId);
    }
}
