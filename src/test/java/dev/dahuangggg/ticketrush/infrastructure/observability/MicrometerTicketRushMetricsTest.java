package dev.dahuangggg.ticketrush.infrastructure.observability;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class MicrometerTicketRushMetricsTest {

    @Test
    void recordsOnlyTheDeclaredLowCardinalityOutcomeTags() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        TicketRushMetrics metrics = new MicrometerTicketRushMetrics(registry);

        metrics.recordRushOutcome(TicketRushMetrics.RushOutcome.RESERVED);
        metrics.recordKafkaSend(TicketRushMetrics.KafkaSendOutcome.SUCCESS, Duration.ofMillis(12));

        assertThat(registry.get("ticket_rush_requests_total")
                .tag("outcome", "reserved").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("ticket_rush_kafka_send")
                .tag("outcome", "success").timer().count()).isEqualTo(1L);
    }
}
