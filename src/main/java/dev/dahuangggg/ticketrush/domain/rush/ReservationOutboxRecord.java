package dev.dahuangggg.ticketrush.domain.rush;

/**
 * Redis Stream 中等待转发到 Kafka 的 Reservation。
 * streamId 只属于 Outbox Adapter，用于 Kafka ACK 后删除记录。
 */
public record ReservationOutboxRecord(
        String streamId,
        String reservationId,
        Long userId,
        Long eventId,
        Long skuId,
        Integer quantity,
        Long unitPrice,
        Long createdAtEpochSecond
) {
}
