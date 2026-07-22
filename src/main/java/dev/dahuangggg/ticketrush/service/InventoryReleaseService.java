package dev.dahuangggg.ticketrush.service;

/**
 * Inventory Release Intent Module。
 * schedule 必须加入调用方的 MySQL 事务；Redis 副作用由可重放 Worker 在提交后执行。
 */
public interface InventoryReleaseService {

    /**
     * 创单前拒绝 Reservation。只有 RESERVED/QUEUED 能转换；如果并发创单已经获胜则返回 false。
     */
    boolean scheduleBeforeOrder(
            String reservationId, Long userId, Long skuId, String reason);

    /**
     * 订单取消或超时后释放 Reservation。必须从 ORDER_CREATED 转换，否则抛错并回滚订单事务。
     */
    void scheduleAfterOrder(
            String reservationId, Long orderId, Long userId, Long skuId, String reason);

    void processPending();

    /** stock key 灾备重建前的数据库屏障；PENDING/FAILED 都属于未结意图。 */
    boolean hasUnsettledForSku(Long skuId);

    /** 管理员在依赖恢复后显式重驱已耗尽的意图；不会新建第二条意图。 */
    boolean requeueFailed(String reservationId);
}
