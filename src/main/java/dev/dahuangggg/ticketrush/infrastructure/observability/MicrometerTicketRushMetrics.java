package dev.dahuangggg.ticketrush.infrastructure.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.time.Duration;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;

/** Prometheus-compatible Adapter for {@link TicketRushMetrics}. */
public final class MicrometerTicketRushMetrics implements TicketRushMetrics {

    private final Map<RushOutcome, Counter> rushOutcomes;
    private final Map<KafkaSendOutcome, Timer> kafkaSendTimers;
    private final Map<OrderCreationOutcome, Timer> orderCreationTimers;
    private final Map<ReleaseOutcome, Counter> releaseOutcomes;
    private final Map<HotSpotSignalOutcome, Counter> hotSpotSignals;
    private final Map<CacheLockOutcome, Counter> cacheLocks;

    public MicrometerTicketRushMetrics(MeterRegistry registry) {
        this.rushOutcomes = counters(registry, "ticket_rush_requests_total", "outcome", RushOutcome.class);
        this.kafkaSendTimers = timers(registry, "ticket_rush_kafka_send", "outcome", KafkaSendOutcome.class);
        this.orderCreationTimers = timers(registry, "ticket_rush_order_creation", "outcome", OrderCreationOutcome.class);
        this.releaseOutcomes = counters(registry, "ticket_rush_release_total", "outcome", ReleaseOutcome.class);
        this.hotSpotSignals = counters(registry, "ticket_rush_hotspot_signal_total", "outcome", HotSpotSignalOutcome.class);
        this.cacheLocks = counters(registry, "ticket_rush_cache_lock_total", "outcome", CacheLockOutcome.class);
    }

    @Override
    public void recordRushOutcome(RushOutcome outcome) {
        rushOutcomes.get(outcome).increment();
    }

    @Override
    public void recordKafkaSend(KafkaSendOutcome outcome, Duration duration) {
        kafkaSendTimers.get(outcome).record(duration);
    }

    @Override
    public void recordOrderCreation(OrderCreationOutcome outcome, Duration duration) {
        orderCreationTimers.get(outcome).record(duration);
    }

    @Override
    public void recordRelease(ReleaseOutcome outcome) {
        releaseOutcomes.get(outcome).increment();
    }

    @Override
    public void recordHotSpotSignal(HotSpotSignalOutcome outcome) {
        hotSpotSignals.get(outcome).increment();
    }

    @Override
    public void recordCacheLock(CacheLockOutcome outcome) {
        cacheLocks.get(outcome).increment();
    }

    private static <E extends Enum<E>> Map<E, Counter> counters(
            MeterRegistry registry, String name, String tagName, Class<E> enumType) {
        Map<E, Counter> result = new EnumMap<>(enumType);
        for (E value : enumType.getEnumConstants()) {
            result.put(value, Counter.builder(name)
                    .tag(tagName, tag(value))
                    .register(registry));
        }
        return result;
    }

    private static <E extends Enum<E>> Map<E, Timer> timers(
            MeterRegistry registry, String name, String tagName, Class<E> enumType) {
        Map<E, Timer> result = new EnumMap<>(enumType);
        for (E value : enumType.getEnumConstants()) {
            result.put(value, Timer.builder(name)
                    .tag(tagName, tag(value))
                    .publishPercentileHistogram()
                    .register(registry));
        }
        return result;
    }

    private static String tag(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT);
    }
}
