package dev.dahuangggg.ticketrush.service;

/**
 * 支付服务接口，提供支付、取消和超时扫描能力。
 */
public interface PaymentService {

    /**
     * 模拟支付，将订单状态从 0（待支付）改为 1（已支付）。
     *
     * @param orderId 订单 ID
     * @param userId  当前登录用户 ID，用于归属校验
     */
    void pay(Long orderId, Long userId);

    /**
     * 用户主动取消订单，将状态从 0 改为 2，并在同一 MySQL 事务写入 Release Intent。
     *
     * @param orderId 订单 ID
     * @param userId  当前登录用户 ID，用于归属校验
     */
    void cancel(Long orderId, Long userId);

    /**
     * 定时扫描超时未支付订单（status=0 且 create_time < now-15min），
     * 逐单使用独立事务将其标记为 3（已超时）并写入 Release Intent；
     * 单笔坏数据不会回滚或阻塞同批后续订单。
     *
     * 由 @Scheduled 每 60 秒调用一次，也可在测试中直接调用。
     */
    void cancelTimeoutOrders();

}
