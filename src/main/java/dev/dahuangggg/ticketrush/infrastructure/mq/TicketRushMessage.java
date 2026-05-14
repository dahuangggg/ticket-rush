package dev.dahuangggg.ticketrush.infrastructure.mq;

/**
 * 抢票请求消息，由抢票接口成功后发送到 Kafka，供订单消费者异步创建订单。
 *
 * messageId 是 UUID，订单消费者用它做幂等去重（避免 Kafka 重试导致重复下单）。
 */
public record TicketRushMessage(
        String messageId,
        Long userId,
        Long eventId,
        Long skuId,
        Integer quantity
) {}
