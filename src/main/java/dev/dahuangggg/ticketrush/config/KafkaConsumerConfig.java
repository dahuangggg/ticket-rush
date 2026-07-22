package dev.dahuangggg.ticketrush.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.util.backoff.ExponentialBackOff;
import org.springframework.util.backoff.FixedBackOff;

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

    /**
     * DLT payloads rejected by the recovery consumer return normally and remain available to a
     * separate manual-review consumer group. An exception means the recovery infrastructure did
     * not durably finish, so this group must keep retrying the same record instead of acknowledging
     * it or cascading into an undefined {@code .DLT.DLT} topic.
     */
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String>
    dltRecoveryKafkaListenerContainerFactory(
            ConsumerFactory<String, String> consumerFactory,
            @Value("${spring.kafka.listener.auto-startup:true}") boolean autoStartup) {
        var factory = new ConcurrentKafkaListenerContainerFactory<String, String>();
        factory.setConsumerFactory(consumerFactory);
        // Custom factories do not inherit Boot's listener auto-startup setting automatically.
        // Respect it so fast/controller tests never depend on a broker being present.
        factory.setAutoStartup(autoStartup);
        factory.setCommonErrorHandler(new DefaultErrorHandler(dltRecoveryBackOff()));
        return factory;
    }

    static FixedBackOff dltRecoveryBackOff() {
        return new FixedBackOff(1_000L, FixedBackOff.UNLIMITED_ATTEMPTS);
    }
}
