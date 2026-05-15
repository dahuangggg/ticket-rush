package dev.dahuangggg.ticketrush.infrastructure.mq;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.dahuangggg.ticketrush.infrastructure.cache.EventCacheManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class EventCacheInvalidationConsumer {

    private static final Logger log = LoggerFactory.getLogger(EventCacheInvalidationConsumer.class);

    private final EventCacheManager eventCacheManager;
    private final ObjectMapper objectMapper;

    public EventCacheInvalidationConsumer(EventCacheManager eventCacheManager,
                                          ObjectMapper objectMapper) {
        this.eventCacheManager = eventCacheManager;
        this.objectMapper = objectMapper;
    }

    /**
     * 消费缓存失效消息，执行缓存删除。
     *
     * Kafka 的梯度重试机制（earliest offset reset + consumer group）保证：
     * 如果本次删除失败（Redis 短暂不可用），消息会被重新消费，梯度重试直到成功。
     * EventCacheManager.invalidate() 调用 Redis DEL，DEL 是幂等操作，
     * 多次消费同一消息不会产生副作用。
     */
    @KafkaListener(topics = EventCacheInvalidationProducer.TOPIC,
            groupId = "${spring.kafka.consumer.group-id}")
    public void consume(String message) {
        try {
            EventCacheInvalidateMessage msg = objectMapper.readValue(
                    message, EventCacheInvalidateMessage.class);
            eventCacheManager.invalidate(msg.eventId());
            log.info("Cache invalidated via Kafka for eventId={}", msg.eventId());
        } catch (JsonProcessingException e) {
            log.error("Failed to deserialize cache invalidation message: {}", message, e);
            // 反序列化失败不重试（消息格式错误，重试无意义）
        } catch (Exception e) {
            log.warn("Cache invalidation failed for message={}, will be retried by error handler", message);
            throw e;  // 向上抛出，触发 DefaultErrorHandler 指数退避重试
        }
    }
}
