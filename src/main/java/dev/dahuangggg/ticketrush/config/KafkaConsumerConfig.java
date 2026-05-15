package dev.dahuangggg.ticketrush.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.util.backoff.ExponentialBackOff;

/**
 * Kafka 消费者错误处理配置。
 *
 * 替换 Spring Kafka 默认的 DefaultErrorHandler（10 次重试，间隔 0ms），
 * 改为指数退避 + 死信 topic，避免 Redis 短暂故障时重试瞬间耗尽导致消息丢失。
 */
@Configuration
public class KafkaConsumerConfig {

    @Bean
    public DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> kafkaTemplate) {
        // 重试耗尽后转发到 {原topic}.DLT，消息不丢失
        DeadLetterPublishingRecoverer recoverer =
                new DeadLetterPublishingRecoverer(kafkaTemplate);

        // 指数退避：初始 1s，翻倍增长，上限 30s，总耗时上限 2 分钟
        // 覆盖 Redis 短暂抖动（通常几秒到几十秒）
        ExponentialBackOff backOff = new ExponentialBackOff(1000L, 2.0);
        backOff.setMaxInterval(30_000L);
        backOff.setMaxElapsedTime(120_000L);

        return new DefaultErrorHandler(recoverer, backOff);
    }
}
