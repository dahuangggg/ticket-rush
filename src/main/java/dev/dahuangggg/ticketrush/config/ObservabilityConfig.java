package dev.dahuangggg.ticketrush.config;

import dev.dahuangggg.ticketrush.infrastructure.observability.MicrometerTicketRushMetrics;
import dev.dahuangggg.ticketrush.infrastructure.observability.NoOpTicketRushMetrics;
import dev.dahuangggg.ticketrush.infrastructure.observability.TicketRushMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ObservabilityConfig {

    @Bean
    TicketRushMetrics ticketRushMetrics(
            MeterRegistry registry,
            @Value("${ticket-rush.observability.metrics-enabled:true}") boolean metricsEnabled) {
        return metricsEnabled
                ? new MicrometerTicketRushMetrics(registry)
                : new NoOpTicketRushMetrics();
    }
}
