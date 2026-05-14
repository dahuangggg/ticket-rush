package dev.dahuangggg.ticketrush.infrastructure.mq;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
public class TicketRushProducer {

    private static final Logger log = LoggerFactory.getLogger(TicketRushProducer.class);
    static final String TOPIC = "ticket.rush.requests";

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    public TicketRushProducer(KafkaTemplate<String, String> kafkaTemplate,
                              ObjectMapper objectMapper) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * 发送抢票消息。
     *
     * 使用 userId + skuId 作为 Kafka partition key，保证同一用户对同一票档的消息
     * 进入同一分区，便于消费者按分区顺序处理（配合 uk_user_sku 唯一键做最终幂等）。
     */
    public void send(TicketRushMessage message) {
        try {
            String json = objectMapper.writeValueAsString(message);
            String partitionKey = message.userId() + "-" + message.skuId();
            kafkaTemplate.send(TOPIC, partitionKey, json);
        } catch (JsonProcessingException e) {
            log.error("序列化抢票消息失败 userId={} skuId={}", message.userId(), message.skuId(), e);
        } catch (Exception e) {
            log.error("发送抢票消息失败 userId={} skuId={}", message.userId(), message.skuId(), e);
        }
    }
}
