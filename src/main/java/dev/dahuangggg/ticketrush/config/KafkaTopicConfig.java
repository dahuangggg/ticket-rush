package dev.dahuangggg.ticketrush.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/** Explicit Kafka topology; avoids environment-dependent broker auto-create defaults. */
@Configuration
public class KafkaTopicConfig {

    public static final String RUSH_TOPIC = "ticket.rush.requests";
    public static final String CACHE_INVALIDATION_TOPIC = "event.cache.invalidate";

    @Bean
    NewTopic ticketRushTopic(
            @Value("${ticket-rush.kafka.rush-topic-partitions:12}") int partitions,
            @Value("${ticket-rush.kafka.topic-replication-factor:1}") short replicas) {
        return topic(RUSH_TOPIC, partitions, replicas);
    }

    @Bean
    NewTopic ticketRushDeadLetterTopic(
            @Value("${ticket-rush.kafka.rush-topic-partitions:12}") int partitions,
            @Value("${ticket-rush.kafka.topic-replication-factor:1}") short replicas) {
        return topic(RUSH_TOPIC + ".DLT", partitions, replicas);
    }

    @Bean
    NewTopic eventCacheInvalidationTopic(
            @Value("${ticket-rush.kafka.cache-topic-partitions:3}") int partitions,
            @Value("${ticket-rush.kafka.topic-replication-factor:1}") short replicas) {
        return topic(CACHE_INVALIDATION_TOPIC, partitions, replicas);
    }

    @Bean
    NewTopic eventCacheInvalidationDeadLetterTopic(
            @Value("${ticket-rush.kafka.cache-topic-partitions:3}") int partitions,
            @Value("${ticket-rush.kafka.topic-replication-factor:1}") short replicas) {
        return topic(CACHE_INVALIDATION_TOPIC + ".DLT", partitions, replicas);
    }

    private static NewTopic topic(String name, int partitions, short replicas) {
        if (partitions < 1) {
            throw new IllegalArgumentException("Kafka topic partitions must be >= 1: " + name);
        }
        if (replicas < 1) {
            throw new IllegalArgumentException("Kafka topic replicas must be >= 1: " + name);
        }
        return TopicBuilder.name(name)
                .partitions(partitions)
                .replicas(replicas)
                .build();
    }
}
