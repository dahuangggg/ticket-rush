package dev.dahuangggg.ticketrush.infrastructure.redis;

/**
 * 集中管理项目所有 Redis key 的构造逻辑。
 * 任何需要拼接 Redis key 的地方都应通过此类获取，避免前缀散落在多个服务中导致不一致。
 */
public final class RedisKeyRegistry {

    private RedisKeyRegistry() {}

    // ========== 抢票模块 ==========

    /**
     * 同一 SKU 的抢票 key 都使用相同 Redis Cluster hash tag。
     * 这样预占和释放 Lua 脚本可以安全地一次操作多个 key，而不会触发 CROSSSLOT。
     */
    public static String stockKey(Long skuId) {
        return rushPrefix(skuId) + ":stock";
    }

    public static String orderUserKey(Long skuId) {
        return rushPrefix(skuId) + ":buyers";
    }

    public static String skuMetadataKey(Long skuId) {
        return rushPrefix(skuId) + ":meta";
    }

    public static String reservationKey(Long skuId, String reservationId) {
        return rushPrefix(skuId) + ":reservation:" + reservationId;
    }

    public static String idempotencyKey(Long skuId, Long userId, String requestHash) {
        return rushPrefix(skuId) + ":request:" + userId + ":" + requestHash;
    }

    public static String reservationOutboxKey(Long skuId) {
        return rushPrefix(skuId) + ":outbox";
    }

    public static String reservationOutboxQuarantineKey(Long skuId) {
        return rushPrefix(skuId) + ":outbox:quarantine";
    }

    /**
     * Relay 用于发现已初始化 SKU 的注册表。它不参与多 key Lua，因此无需与 SKU key 共槽。
     */
    public static String reservationOutboxSkuRegistryKey() {
        return "ticket:rush:outbox:skus";
    }

    public static String reservationOutboxRelayLock(Long skuId) {
        return "lock:ticket:rush:outbox:" + skuId;
    }

    private static String rushPrefix(Long skuId) {
        return "ticket:{" + skuId + "}";
    }

    public static String releaseRetryLock() {
        return "lock:ticket:release:retry";
    }

    /**
     * 释放 Worker 与缺失 stock key 的重建流程必须使用同一把 SKU 锁。
     * hash tag 让锁与该 SKU 的业务 key 落在同一 Redis Cluster slot，便于故障域审计。
     */
    public static String inventoryMutationLock(Long skuId) {
        return rushPrefix(skuId) + ":inventory-mutation-lock";
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
        return "auth:sms-code:{" + phone + "}";
    }

    public static String smsCooldownKey(String phone) {
        return "auth:sms-cooldown:{" + phone + "}";
    }

    public static String refreshTokenKey(String token) {
        return "auth:refresh-token:" + token;
    }

    // ========== AI 助手模块 ==========

    public static String aiChatMemoryKey(String sessionId) {
        return "ai:chat:" + sessionId;
    }

    // ========== 开抢提醒模块 ==========

    public static String reminderDueZset() {
        return "reminder:due";
    }

    public static String reminderScanLock() {
        return "reminder:scan:lock";
    }
}
