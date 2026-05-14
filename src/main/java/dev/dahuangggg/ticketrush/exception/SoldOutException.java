package dev.dahuangggg.ticketrush.exception;

public class SoldOutException extends RuntimeException {

    public SoldOutException(Long skuId) {
        super("票档已售罄：" + skuId);
    }
}
