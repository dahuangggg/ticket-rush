package dev.dahuangggg.ticketrush.dto.rush;

/**
 * 抢票提交与 Reservation 查询共用的稳定响应。
 * 客户端应保存 reservationId；HTTP 响应丢失时使用相同 Idempotency-Key 重试会得到同一 ID。
 */
public record TicketRushResponse(String reservationId, String status, Long orderId) {
}
