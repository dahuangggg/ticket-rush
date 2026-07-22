package dev.dahuangggg.ticketrush.infrastructure.observability;

import java.time.Duration;

/** Adapter used by fast tests or deployments that explicitly disable metrics. */
public final class NoOpTicketRushMetrics implements TicketRushMetrics {

    @Override public void recordRushOutcome(RushOutcome outcome) {}
    @Override public void recordKafkaSend(KafkaSendOutcome outcome, Duration duration) {}
    @Override public void recordOrderCreation(OrderCreationOutcome outcome, Duration duration) {}
    @Override public void recordRelease(ReleaseOutcome outcome) {}
    @Override public void recordHotSpotSignal(HotSpotSignalOutcome outcome) {}
    @Override public void recordCacheLock(CacheLockOutcome outcome) {}
}
