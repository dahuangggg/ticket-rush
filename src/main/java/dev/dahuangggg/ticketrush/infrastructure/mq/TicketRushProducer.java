package dev.dahuangggg.ticketrush.infrastructure.mq;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.dahuangggg.ticketrush.exception.KafkaPublishException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Component
public class TicketRushProducer {

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
            kafkaTemplate.send(TOPIC, partitionKey, json).get(3, TimeUnit.SECONDS);
        } catch (JsonProcessingException e) {
            throw new KafkaPublishException("序列化抢票消息失败", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new KafkaPublishException("发送抢票消息被中断", e);
        } catch (ExecutionException | TimeoutException e) {
            throw new KafkaPublishException("发送抢票消息失败", e);
        }
    }
}
