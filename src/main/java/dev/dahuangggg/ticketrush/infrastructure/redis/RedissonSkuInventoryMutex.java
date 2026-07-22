package dev.dahuangggg.ticketrush.infrastructure.redis;

import dev.dahuangggg.ticketrush.service.SkuInventoryMutex;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * 多实例使用 Redisson 分布式锁；禁用 Redisson 的单实例教学模式使用进程内锁。
 */
@Component
public class RedissonSkuInventoryMutex implements SkuInventoryMutex {

    private final RedissonClient redissonClient;
    private final ConcurrentHashMap<Long, ReentrantLock> localLocks = new ConcurrentHashMap<>();

    public RedissonSkuInventoryMutex(Optional<RedissonClient> redissonClient) {
        this.redissonClient = redissonClient.orElse(null);
    }

    @Override
    public <T> T execute(Long skuId, Supplier<T> action) {
        if (redissonClient != null) {
            RLock lock = redissonClient.getLock(RedisKeyRegistry.inventoryMutationLock(skuId));
            lock.lock();
            try {
                return action.get();
            } finally {
                if (lock.isHeldByCurrentThread()) {
                    lock.unlock();
                }
            }
        }

        ReentrantLock lock = localLocks.computeIfAbsent(skuId, ignored -> new ReentrantLock());
        lock.lock();
        try {
            return action.get();
        } finally {
            lock.unlock();
        }
    }
}
