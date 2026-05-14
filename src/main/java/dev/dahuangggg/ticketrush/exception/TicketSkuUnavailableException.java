package dev.dahuangggg.ticketrush.exception;

public class TicketSkuUnavailableException extends RuntimeException {
    public TicketSkuUnavailableException(Long skuId) {
        super("票档当前不可抢：" + skuId);
    }
}
