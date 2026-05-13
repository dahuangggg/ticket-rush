package dev.dahuangggg.ticketrush.exception;

/**
 * 活动不存在异常。
 *
 * 以下情况抛出：
 * 1. Bloom Filter 判断 eventId 一定不存在。
 * 2. Redis 命中空值标记（活动曾被查询过且 DB 确认不存在）。
 * 3. DB 查询结果为 null（活动不存在或已逻辑删除）。
 */
public class EventNotFoundException extends RuntimeException {

    public EventNotFoundException(Long eventId) {
        super("活动不存在：" + eventId);
    }
}
