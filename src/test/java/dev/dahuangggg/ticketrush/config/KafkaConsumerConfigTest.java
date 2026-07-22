package dev.dahuangggg.ticketrush.config;

import org.junit.jupiter.api.Test;
import org.springframework.util.backoff.BackOffExecution;
import org.springframework.util.backoff.FixedBackOff;

import static org.assertj.core.api.Assertions.assertThat;

class KafkaConsumerConfigTest {

    @Test
    void dltInfrastructureFailuresNeverExhaustIntoALogOnlyRecovery() {
        FixedBackOff backOff = KafkaConsumerConfig.dltRecoveryBackOff();

        assertThat(backOff.getInterval()).isEqualTo(1_000L);
        assertThat(backOff.getMaxAttempts()).isEqualTo(FixedBackOff.UNLIMITED_ATTEMPTS);

        BackOffExecution execution = backOff.start();
        // The previous configuration stopped after five retries. Prove that the configured
        // execution continues past that boundary instead of returning BackOffExecution.STOP.
        for (int attempt = 0; attempt < 10; attempt++) {
            assertThat(execution.nextBackOff()).isEqualTo(1_000L);
        }
    }
}
