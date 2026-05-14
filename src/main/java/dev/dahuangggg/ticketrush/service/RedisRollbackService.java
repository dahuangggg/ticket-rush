package dev.dahuangggg.ticketrush.service;

public interface RedisRollbackService {

    void rollback(Long skuId, Long userId);
}
