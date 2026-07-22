package dev.dahuangggg.ticketrush.infrastructure.mq;

import dev.dahuangggg.ticketrush.domain.rush.ReservationDecision;
import dev.dahuangggg.ticketrush.domain.rush.ReservationOutboxRecord;
import dev.dahuangggg.ticketrush.domain.rush.ReservationSnapshot;
import dev.dahuangggg.ticketrush.domain.rush.ReservationStatus;
import dev.dahuangggg.ticketrush.entity.RushReservation;
import dev.dahuangggg.ticketrush.entity.TicketSku;
import dev.dahuangggg.ticketrush.exception.KafkaPublishException;
import dev.dahuangggg.ticketrush.infrastructure.observability.NoOpTicketRushMetrics;
import dev.dahuangggg.ticketrush.service.ReservationOutbox;
import dev.dahuangggg.ticketrush.service.RushReservationLedger;
import dev.dahuangggg.ticketrush.service.RushReservationStore;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.time.LocalDateTime;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ReservationOutboxRelayTest {

    @Test
    void kafkaUnknownResult_keepsOutboxAndRetriesWithSameReservationId() {
        FakeOutbox outbox = new FakeOutbox();
        FakeLedger ledger = new FakeLedger();
        FakeReservationStore store = new FakeReservationStore();
        FakeProducer producer = new FakeProducer();
        producer.fail = true;
        ReservationOutboxRelay relay = new ReservationOutboxRelay(
                outbox, ledger, store, producer, Optional.empty(), 10, 5, Clock.systemUTC());

        relay.relay();

        assertThat(ledger.recordCount).isOne();
        assertThat(outbox.acknowledged).isFalse();
        assertThat(producer.lastMessage.reservationId()).isEqualTo("3001-reservation");

        producer.fail = false;
        relay.relay();

        assertThat(outbox.acknowledged).isTrue();
        assertThat(store.queuedReservationId).isEqualTo("3001-reservation");
        assertThat(producer.sendCount).isEqualTo(2);
    }

    @Test
    void missingRedisJournalIsRepublishedFromDurableReservedLedger() {
        FakeOutbox outbox = new FakeOutbox();
        outbox.acknowledged = true;
        FakeLedger ledger = new FakeLedger();
        ledger.orphan = RushReservation.builder()
                .reservationId("3001-durable-orphan")
                .userId(10L)
                .eventId(20L)
                .skuId(3001L)
                .quantity(1)
                .unitPrice(38000L)
                .status(ReservationStatus.RESERVED.code())
                .createTime(LocalDateTime.now().minusMinutes(1))
                .build();
        FakeProducer producer = new FakeProducer();
        ReservationOutboxRelay relay = new ReservationOutboxRelay(
                outbox, ledger, new FakeReservationStore(), producer, Optional.empty(), 10, 5,
                Clock.systemUTC());

        relay.relay();

        assertThat(producer.lastMessage.reservationId()).isEqualTo("3001-durable-orphan");
        assertThat(ledger.orphan.getStatus()).isEqualTo(ReservationStatus.QUEUED.code());
    }

    @Test
    void redisJournalFailureDoesNotBlockDurableRecovery() {
        FakeOutbox outbox = new FakeOutbox();
        outbox.failPending = true;
        FakeLedger ledger = new FakeLedger();
        ledger.orphan = RushReservation.builder()
                .reservationId("3001-durable-after-redis-failure")
                .userId(10L)
                .eventId(20L)
                .skuId(3001L)
                .quantity(1)
                .unitPrice(38000L)
                .status(ReservationStatus.RESERVED.code())
                .createTime(LocalDateTime.now().minusMinutes(1))
                .build();
        FakeProducer producer = new FakeProducer();
        ReservationOutboxRelay relay = new ReservationOutboxRelay(
                outbox, ledger, new FakeReservationStore(), producer, Optional.empty(), 10, 5,
                Clock.systemUTC());

        relay.relay();

        assertThat(producer.lastMessage.reservationId())
                .isEqualTo("3001-durable-after-redis-failure");
    }

    private static final class FakeOutbox implements ReservationOutbox {
        private final ReservationOutboxRecord record = new ReservationOutboxRecord(
                "1-0", "3001-reservation", 10L, 20L, 3001L, 1, 38000L, 100L);
        private boolean acknowledged;
        private boolean failPending;

        @Override public void registerSku(Long skuId) { }
        @Override public Set<Long> registeredSkuIds() { return Set.of(3001L); }
        @Override public List<ReservationOutboxRecord> pending(Long skuId, int limit) {
            if (failPending) throw new IllegalStateException("redis unavailable");
            return acknowledged ? List.of() : List.of(record);
        }
        @Override public void acknowledge(Long skuId, String streamId) { acknowledged = true; }
    }

    private static final class FakeProducer extends TicketRushProducer {
        private boolean fail;
        private int sendCount;
        private TicketRushMessage lastMessage;

        private FakeProducer() {
            super(null, null, new NoOpTicketRushMetrics(), 3);
        }

        @Override public void send(TicketRushMessage message) {
            sendCount++;
            lastMessage = message;
            if (fail) {
                throw new KafkaPublishException("unknown", new RuntimeException("timeout"));
            }
        }
    }

    private static final class FakeLedger implements RushReservationLedger {
        private int recordCount;
        private RushReservation orphan;

        @Override public RushReservation recordReserved(ReservationOutboxRecord record) {
            recordCount++;
            return RushReservation.builder().reservationId(record.reservationId()).build();
        }
        @Override public Optional<RushReservation> find(String reservationId) {
            return orphan != null && orphan.getReservationId().equals(reservationId)
                    ? Optional.of(orphan) : Optional.empty();
        }
        @Override public List<RushReservation> findReservedBefore(LocalDateTime cutoff, int limit) {
            return orphan != null && orphan.getStatus() == ReservationStatus.RESERVED.code()
                    ? List.of(orphan) : List.of();
        }
        @Override public RushReservation requireMatching(TicketRushMessage message) { throw new UnsupportedOperationException(); }
        @Override public void markQueued(String reservationId) {
            if (orphan != null && orphan.getReservationId().equals(reservationId)) {
                orphan.setStatus(ReservationStatus.QUEUED.code());
            }
        }
        @Override public boolean tryMarkOrderCreated(String reservationId, Long orderId) { return true; }
        @Override public boolean tryMarkPaid(String reservationId) { return true; }
        @Override public boolean tryMarkReleasePendingBeforeOrder(String reservationId, String reason) { return true; }
        @Override public boolean tryMarkReleasePendingAfterOrder(String reservationId, String reason) { return true; }
        @Override public void markReleased(String reservationId) {}
        @Override public long countBySkuAndStatuses(Long skuId, Collection<Integer> statuses) { return 0; }
    }

    private static final class FakeReservationStore implements RushReservationStore {
        private String queuedReservationId;

        @Override public boolean initializeSku(TicketSku sku, int availableStock) { return false; }
        @Override public Integer getAvailableStock(Long skuId) { return null; }
        @Override public long getConsumedReservationCount(Long skuId) { return 0; }
        @Override public boolean hasBuyerState(Long skuId) { return false; }
        @Override public ReservationDecision reserve(Long userId, Long eventId, Long skuId, Integer quantity,
                                                     String reservationId, String idempotencyHash) {
            throw new UnsupportedOperationException();
        }
        @Override public Optional<ReservationSnapshot> find(String reservationId) { return Optional.empty(); }
        @Override public void markQueued(String reservationId) { queuedReservationId = reservationId; }
        @Override public void markOrderCreated(String reservationId, Long orderId) {}
        @Override public void markPaid(String reservationId) {}
        @Override public void markRejected(String reservationId, String reason) {}
    }
}
