package dev.dahuangggg.ticketrush.infrastructure.observability;

import java.time.Duration;

/**
 * Low-cardinality operational signals for the ticketing flow.
 *
 * <p>The interface deliberately accepts enums instead of arbitrary tag strings so callers cannot
 * accidentally create unbounded Prometheus cardinality with user, event, SKU, or order IDs.</p>
 */
public interface TicketRushMetrics {

    void recordRushOutcome(RushOutcome outcome);

    void recordKafkaSend(KafkaSendOutcome outcome, Duration duration);

    void recordOrderCreation(OrderCreationOutcome outcome, Duration duration);

    void recordRelease(ReleaseOutcome outcome);

    void recordHotSpotSignal(HotSpotSignalOutcome outcome);

    void recordCacheLock(CacheLockOutcome outcome);

    enum RushOutcome { RESERVED, IDEMPOTENT_REPLAY, SOLD_OUT, DUPLICATE, UNAVAILABLE }

    enum KafkaSendOutcome { SUCCESS, TIMEOUT, FAILURE }

    enum OrderCreationOutcome { CREATED, ALREADY_CREATED, REJECTED, FAILED }

    enum ReleaseOutcome { RELEASED, ALREADY_RELEASED, RETRY, FAILED }

    enum HotSpotSignalOutcome { AGGREGATED, DROPPED, FLUSH_FAILED, PROMOTED }

    enum CacheLockOutcome { ACQUIRED, CONTENDED, RELEASED, STALE_RELEASE_IGNORED, RELEASE_FAILED }
}
