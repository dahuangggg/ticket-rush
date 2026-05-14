package dev.dahuangggg.ticketrush.exception;

/**
 * 订单状态不是待支付时抛出，例如对已支付、已取消、已超时的订单执行支付或取消操作。
 */
public class OrderNotPendingException extends RuntimeException {
    public OrderNotPendingException(Long orderId) {
        super("订单不是待支付状态：" + orderId);
    }
}
