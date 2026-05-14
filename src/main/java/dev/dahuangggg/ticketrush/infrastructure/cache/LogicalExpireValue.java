package dev.dahuangggg.ticketrush.infrastructure.cache;

import java.time.LocalDateTime;

/**
 * 逻辑过期缓存值包装类。
 *
 * 热点活动缓存不设置真实的 Redis TTL，
 * 而是将过期时间存储在 value 内部（expireAt）。
 * 读取时判断逻辑是否过期：
 * - 未过期：直接返回 data
 * - 已过期：返回旧 data，同时异步启动缓存重建，不阻塞当前请求
 *
 * 这样即使缓存"过期"，用户仍能立刻拿到数据，不会出现缓存击穿导致的请求堆积。
 */
public record LogicalExpireValue<T>(T data, LocalDateTime expireAt) {
}
