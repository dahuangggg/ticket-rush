package dev.dahuangggg.ticketrush.exception;

/**
 * 订单不存在异常，当按 ID 或订单号查询到不存在或不属于当前用户的订单时抛出。
 */
public class OrderNotFoundException extends RuntimeException {

    public OrderNotFoundException(Long orderId) {
        super("订单不存在：" + orderId);
    }

    public OrderNotFoundException(String orderNo) {
        super("订单不存在：" + orderNo);
    }
}
