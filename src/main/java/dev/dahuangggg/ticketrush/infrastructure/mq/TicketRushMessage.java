package dev.dahuangggg.ticketrush.infrastructure.mq;

/**
 * 抢票请求消息，由抢票接口成功后发送到 Kafka，供订单消费者异步创建订单。
 *
 * messageId 与 reservationId 均使用 Reservation 的稳定业务 ID。前者是 inbox 幂等键，
 * 后者用于校验 Reservation Ledger；保留两个名字是为了把“消息身份”和“业务身份”讲清楚。
 */
public record TicketRushMessage(
        String messageId,
        String reservationId,
        Long userId,
        Long eventId,
        Long skuId,
        Integer quantity,
        Long unitPrice
) {}
