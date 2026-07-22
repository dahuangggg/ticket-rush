package dev.dahuangggg.ticketrush.infrastructure.observability;

import org.apache.kafka.clients.admin.Admin;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/** Readiness signal for the Kafka dependency used by the reservation flow. */
@Component("kafkaHealthIndicator")
public final class KafkaHealthIndicator implements HealthIndicator {

    private static final long TIMEOUT_SECONDS = 2;
    private final KafkaAdmin kafkaAdmin;

    public KafkaHealthIndicator(KafkaAdmin kafkaAdmin) {
        this.kafkaAdmin = kafkaAdmin;
    }

    @Override
    public Health health() {
        try (Admin admin = Admin.create(kafkaAdmin.getConfigurationProperties())) {
            String clusterId = admin.describeCluster().clusterId().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            return Health.up().withDetail("clusterId", clusterId).build();
        } catch (Exception exception) {
            return Health.down(exception).build();
        }
    }
}
