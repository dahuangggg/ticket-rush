package dev.dahuangggg.ticketrush.exception;

public class DuplicateOrderException extends RuntimeException {

    public DuplicateOrderException(Long skuId) {
        super("请勿重复抢票：" + skuId);
    }
}
