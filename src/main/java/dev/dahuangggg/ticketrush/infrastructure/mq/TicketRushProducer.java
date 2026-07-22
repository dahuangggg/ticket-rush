package dev.dahuangggg.ticketrush.infrastructure.mq;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.dahuangggg.ticketrush.exception.KafkaPublishException;
import dev.dahuangggg.ticketrush.infrastructure.observability.TicketRushMetrics;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.apache.kafka.clients.producer.ProducerRecord;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Component
public class TicketRushProducer {

    public static final String TOPIC = "ticket.rush.requests";
    public static final String RESERVATION_ID_HEADER = "reservationId";

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final long sendTimeoutSeconds;
    private final TicketRushMetrics metrics;

    public TicketRushProducer(KafkaTemplate<String, String> kafkaTemplate,
                              ObjectMapper objectMapper,
                              TicketRushMetrics metrics,
                              @Value("${ticket-rush.kafka.send-timeout-seconds:3}") long sendTimeoutSeconds) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.metrics = metrics;
        this.sendTimeoutSeconds = sendTimeoutSeconds;
    }

    /**
     * 发送抢票消息。
     *
     * 使用 userId + skuId 作为 Kafka partition key，保证同一用户对同一票档的消息
     * 进入同一分区，便于消费者按分区顺序处理（配合 uk_user_sku 唯一键做最终幂等）。
     */
    public void send(TicketRushMessage message) {
        long startedAt = System.nanoTime();
        try {
            String json = objectMapper.writeValueAsString(message);
            String partitionKey = message.userId() + "-" + message.skuId();
            ProducerRecord<String, String> record = new ProducerRecord<>(TOPIC, partitionKey, json);
            record.headers().add(RESERVATION_ID_HEADER,
                    message.reservationId().getBytes(StandardCharsets.UTF_8));
            kafkaTemplate.send(record).get(sendTimeoutSeconds, TimeUnit.SECONDS);
            metrics.recordKafkaSend(TicketRushMetrics.KafkaSendOutcome.SUCCESS, elapsed(startedAt));
        } catch (JsonProcessingException e) {
            metrics.recordKafkaSend(TicketRushMetrics.KafkaSendOutcome.FAILURE, elapsed(startedAt));
            throw new KafkaPublishException("序列化抢票消息失败", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            metrics.recordKafkaSend(TicketRushMetrics.KafkaSendOutcome.FAILURE, elapsed(startedAt));
            throw new KafkaPublishException("发送抢票消息被中断", e);
        } catch (TimeoutException e) {
            metrics.recordKafkaSend(TicketRushMetrics.KafkaSendOutcome.TIMEOUT, elapsed(startedAt));
            throw new KafkaPublishException(
                    "发送抢票消息超时（" + sendTimeoutSeconds + "秒），Kafka broker 可能负载过高", e);
        } catch (ExecutionException e) {
            metrics.recordKafkaSend(TicketRushMetrics.KafkaSendOutcome.FAILURE, elapsed(startedAt));
            throw new KafkaPublishException(
                    "发送抢票消息被拒绝: " + (e.getCause() != null ? e.getCause().getMessage() : e.getMessage()), e);
        }
    }

    private Duration elapsed(long startedAt) {
        return Duration.ofNanos(System.nanoTime() - startedAt);
    }
}
