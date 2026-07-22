package dev.dahuangggg.ticketrush.service;

import dev.dahuangggg.ticketrush.domain.rush.ReservationOutboxRecord;

import java.util.List;
import java.util.Set;

/** Redis Reservation Journal 到 Kafka Relay 的最小 Interface。 */
public interface ReservationOutbox {

    /** 可由 durable SKU catalog 重建的发现索引；重复注册必须幂等。 */
    void registerSku(Long skuId);

    Set<Long> registeredSkuIds();

    List<ReservationOutboxRecord> pending(Long skuId, int limit);

    void acknowledge(Long skuId, String streamId);
}
