package dev.dahuangggg.ticketrush.infrastructure.redis;

/**
 * 集中管理项目所有 Redis key 的构造逻辑。
 * 任何需要拼接 Redis key 的地方都应通过此类获取，避免前缀散落在多个服务中导致不一致。
 */
public final class RedisKeyRegistry {

    private RedisKeyRegistry() {}

    // ========== 抢票模块 ==========

    public static String stockKey(Long skuId) {
        return "ticket:stock:" + skuId;
    }

    public static String orderUserKey(Long skuId) {
        return "ticket:order:user:" + skuId;
    }

    public static String rollbackRetryLock() {
        return "lock:ticket:rollback:retry";
    }

    // ========== 活动缓存模块 ==========

    public static String eventDetailKey(Long eventId) {
        return "event:detail:" + eventId;
    }

    public static String eventHotDetailKey(Long eventId) {
        return "event:detail:hot:" + eventId;
    }

    public static String eventNullKey(Long eventId) {
        return "event:null:" + eventId;
    }

    public static String eventLockKey(Long eventId) {
        return "event:lock:" + eventId;
    }

    public static String eventHotLockKey(Long eventId) {
        return "event:lock:hot:" + eventId;
    }

    public static String eventListKey(String cacheKey) {
        return "event:list:" + cacheKey;
    }

    public static String eventBloomFilterKey() {
        return "event:bloom";
    }

    public static String eventAccessCountKey(Long eventId) {
        return "event:access:count:" + eventId;
    }

    // ========== 认证模块 ==========

    public static String smsCodeKey(String phone) {
        return "auth:sms-code:" + phone;
    }

    public static String smsCooldownKey(String phone) {
        return "auth:sms-cooldown:" + phone;
    }

    public static String refreshTokenKey(String token) {
        return "auth:refresh-token:" + token;
    }
}
