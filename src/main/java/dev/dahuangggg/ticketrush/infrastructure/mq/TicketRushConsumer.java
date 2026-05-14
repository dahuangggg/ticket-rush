package dev.dahuangggg.ticketrush.infrastructure.mq;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.dahuangggg.ticketrush.service.OrderService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * 抢票消息消费者，从 Kafka 消费抢票请求并异步创建订单。
 */
@Component
public class TicketRushConsumer {

    private static final Logger log = LoggerFactory.getLogger(TicketRushConsumer.class);

    private final OrderService orderService;
    private final ObjectMapper objectMapper;

    public TicketRushConsumer(OrderService orderService, ObjectMapper objectMapper) {
        this.orderService = orderService;
        this.objectMapper = objectMapper;
    }

    /**
     * 消费抢票消息，调用订单服务异步创建订单。
     *
     * 反序列化失败不重试（消息格式不可修复）；
     * 业务异常不在此捕获，让异常上抛触发 Kafka 重试。
     */
    @KafkaListener(topics = TicketRushProducer.TOPIC, groupId = "${spring.kafka.consumer.group-id}")
    public void consume(String message) {
        try {
            TicketRushMessage msg = objectMapper.readValue(message, TicketRushMessage.class);
            orderService.createOrder(msg);
        } catch (JsonProcessingException e) {
            log.error("抢票消息反序列化失败，跳过: {}", message, e);
        }
    }
}
