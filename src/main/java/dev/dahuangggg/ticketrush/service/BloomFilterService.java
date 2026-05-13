package dev.dahuangggg.ticketrush.service;

/**
 * 布隆过滤器服务接口。
 *
 * 用于活动详情查询的缓存穿透防护：
 * - 启动时加载所有合法 eventId
 * - 每次查询前先检查 eventId 是否可能存在
 * - "一定不存在"则直接返回 404，不查 Redis 和 DB
 * - "可能存在"则继续正常查询流程
 *
 * 注意：布隆过滤器不支持删除，活动软删除后其 ID 仍在过滤器中，
 * 此时会穿透到 DB 查询，再由空值缓存兜底。
 */
public interface BloomFilterService {

    /**
     * 判断 eventId 是否可能存在。
     * 返回 false 表示一定不存在，可直接拒绝。
     * 返回 true 表示可能存在（含误判），需继续查询。
     */
    boolean mightExist(Long eventId);

    /**
     * 将 eventId 加入布隆过滤器。
     * 新增活动时调用，保证合法 ID 不被误拦截。
     */
    void add(Long eventId);
}
