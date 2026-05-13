package dev.dahuangggg.ticketrush.kafka;

/**
 * 活动缓存失效消息。
 *
 * 只携带 eventId，不携带 Redis key 字符串。
 * Key 的拼接逻辑由消费者端的 EventCacheManager.invalidate() 统一管理，
 * 避免 key 格式变更时历史消息失效。
 */
public record EventCacheInvalidateMessage(Long eventId) {
}
