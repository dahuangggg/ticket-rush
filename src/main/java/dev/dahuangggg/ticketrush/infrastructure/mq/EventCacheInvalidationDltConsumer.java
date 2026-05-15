package dev.dahuangggg.ticketrush.infrastructure.mq;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * 缓存失效死信 topic 消费者。
 *
 * 当 {@link EventCacheInvalidationConsumer} 重试耗尽后，
 * 消息被 {@link org.springframework.kafka.listener.DeadLetterPublishingRecoverer}
 * 转发到 event.cache.invalidate.DLT。此消费者记录告警日志，
 * 生产环境可接入钉钉/飞书/PagerDuty 等告警渠道。
 */
@Component
public class EventCacheInvalidationDltConsumer {

    private static final Logger log = LoggerFactory.getLogger(EventCacheInvalidationDltConsumer.class);

    @KafkaListener(topics = EventCacheInvalidationProducer.TOPIC + ".DLT",
            groupId = "${spring.kafka.consumer.group-id}-dlt")
    public void consume(String message) {
        log.error("Cache invalidation message exhausted all retries, manual intervention required: {}", message);
    }
}
