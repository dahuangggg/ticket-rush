package dev.dahuangggg.ticketrush.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
public class EventCacheInvalidationProducer {

    private static final Logger log = LoggerFactory.getLogger(EventCacheInvalidationProducer.class);
    static final String TOPIC = "event.cache.invalidate";

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    public EventCacheInvalidationProducer(KafkaTemplate<String, String> kafkaTemplate,
                                          ObjectMapper objectMapper) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * 发送缓存失效消息。
     *
     * 仅在同步删除缓存失败时调用，作为异步兜底手段。
     * 使用 eventId 作为 Kafka partition key，保证同一活动的消息有序消费。
     * 发送失败时记录错误日志，依赖 TTL 自然过期兜底（最终一致性的最后防线）。
     */
    public void send(Long eventId) {
        try {
            String message = objectMapper.writeValueAsString(new EventCacheInvalidateMessage(eventId));
            kafkaTemplate.send(TOPIC, String.valueOf(eventId), message);
            log.info("Cache invalidation message sent for eventId={}", eventId);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize cache invalidation message for eventId={}", eventId, e);
        } catch (Exception e) {
            log.error("Failed to send cache invalidation message for eventId={}, will rely on TTL expiry",
                    eventId, e);
        }
    }
}
