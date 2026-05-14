package dev.dahuangggg.ticketrush.exception;

/**
 * 票档不存在异常。
 */
public class TicketSkuNotFoundException extends RuntimeException {

    public TicketSkuNotFoundException(Long skuId) {
        super("票档不存在：" + skuId);
    }
}
