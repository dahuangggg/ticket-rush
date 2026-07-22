package dev.dahuangggg.ticketrush.service;

/**
 * 单笔超时订单处理边界。
 *
 * <p>实现必须让订单 CAS 与 Release Intent 在同一独立事务中提交。</p>
 */
public interface TimeoutOrderProcessor {

    /**
     * 尝试把一笔待支付订单置为超时并创建 Release Intent。
     *
     * @return CAS 成功并创建（或确认存在）Release Intent 时返回 {@code true}；
     *         订单已被并发支付/取消或不存在时返回 {@code false}
     */
    boolean timeout(Long orderId);
}
