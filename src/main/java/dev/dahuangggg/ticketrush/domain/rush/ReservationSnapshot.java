package dev.dahuangggg.ticketrush.domain.rush;

import java.time.Instant;

/**
 * Reservation 的稳定领域视图，不暴露 Redis Hash 或数据库实体。
 */
public record ReservationSnapshot(
        String reservationId,
        Long userId,
        Long eventId,
        Long skuId,
        Integer quantity,
        Long unitPrice,
        ReservationStatus status,
        Long orderId,
        Instant createdAt,
        String reason
) {
}
