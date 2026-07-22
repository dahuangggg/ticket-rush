package dev.dahuangggg.ticketrush.service;

import java.util.function.Supplier;

/**
 * 同一 SKU 的 Redis 库存释放与灾备重建之间的互斥边界。
 *
 * <p>锁只保护短小的恢复临界区；业务状态仍由 MySQL Ledger 与 Redis Lua 决定。</p>
 */
public interface SkuInventoryMutex {

    <T> T execute(Long skuId, Supplier<T> action);
}
