package dev.dahuangggg.ticketrush.domain.rush;

/**
 * Redis Lua 预占的业务结果。重复的 Idempotency-Key 会返回原 Reservation，而不是再次扣库存。
 */
public record ReservationDecision(Type type, String reservationId) {

    public enum Type {
        RESERVED,
        EXISTING,
        SOLD_OUT,
        DUPLICATE_USER,
        SKU_UNAVAILABLE
    }
}
