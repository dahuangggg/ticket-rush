package dev.dahuangggg.ticketrush.infrastructure.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.dahuangggg.ticketrush.domain.rush.ReservationDecision;
import dev.dahuangggg.ticketrush.domain.rush.ReservationOutboxRecord;
import dev.dahuangggg.ticketrush.domain.rush.ReservationSnapshot;
import dev.dahuangggg.ticketrush.domain.rush.ReservationStatus;
import dev.dahuangggg.ticketrush.entity.RushReservation;
import dev.dahuangggg.ticketrush.entity.TicketSku;
import dev.dahuangggg.ticketrush.service.InventoryReleaseService;
import dev.dahuangggg.ticketrush.service.RushReservationLedger;
import dev.dahuangggg.ticketrush.service.RushReservationStore;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Collection;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class TicketRushDltRecoveryConsumerTest {

    @Test
    void concurrentOrderWinnerPreventsDltReleaseAndRedisRejection() {
        String reservationId = "3001-race";
        FakeReservationStore store = new FakeReservationStore();
        FakeReleaseService release = new FakeReleaseService(false);
        RushReservation durable = RushReservation.builder()
                .reservationId(reservationId)
                .userId(7L)
                .eventId(20L)
                .skuId(3001L)
                .quantity(1)
                .unitPrice(38000L)
                .status(ReservationStatus.QUEUED.code())
                .build();
        TicketRushDltRecoveryConsumer consumer = new TicketRushDltRecoveryConsumer(
                store, new FakeLedger(durable), release, new ObjectMapper());
        ConsumerRecord<String, String> record = new ConsumerRecord<>(
                TicketRushProducer.TOPIC + ".DLT", 0, 1L, "7-3001",
                json(new TicketRushMessage(
                        reservationId, reservationId, 7L, 20L, 3001L, 1, 38000L)));
        record.headers().add(TicketRushProducer.RESERVATION_ID_HEADER,
                reservationId.getBytes(StandardCharsets.UTF_8));

        consumer.recover(record);

        assertThat(release.calls).isOne();
        assertThat(store.rejected).isFalse();
    }

    @Test
    void forgedHeaderCannotReleaseDifferentPayloadReservation() {
        RushReservation durable = RushReservation.builder()
                .reservationId("3001-victim")
                .userId(7L)
                .eventId(20L)
                .skuId(3001L)
                .quantity(1)
                .unitPrice(38000L)
                .status(ReservationStatus.QUEUED.code())
                .build();
        FakeReleaseService release = new FakeReleaseService(true);
        TicketRushDltRecoveryConsumer consumer = new TicketRushDltRecoveryConsumer(
                new FakeReservationStore(), new FakeLedger(durable), release, new ObjectMapper());
        ConsumerRecord<String, String> record = new ConsumerRecord<>(
                TicketRushProducer.TOPIC + ".DLT", 0, 2L, "attacker",
                json(new TicketRushMessage(
                        "3001-attacker", "3001-attacker", 9L, 20L, 3001L, 1, 38000L)));
        record.headers().add(TicketRushProducer.RESERVATION_ID_HEADER,
                "3001-victim".getBytes(StandardCharsets.UTF_8));

        consumer.recover(record);

        assertThat(release.calls).isZero();
    }

    @Test
    void corruptPayloadRequiresManualReviewInsteadOfAutomaticRelease() {
        RushReservation durable = RushReservation.builder()
                .reservationId("3001-victim")
                .userId(7L)
                .eventId(20L)
                .skuId(3001L)
                .quantity(1)
                .unitPrice(38000L)
                .status(ReservationStatus.QUEUED.code())
                .build();
        FakeReleaseService release = new FakeReleaseService(true);
        TicketRushDltRecoveryConsumer consumer = new TicketRushDltRecoveryConsumer(
                new FakeReservationStore(), new FakeLedger(durable), release, new ObjectMapper());
        ConsumerRecord<String, String> record = new ConsumerRecord<>(
                TicketRushProducer.TOPIC + ".DLT", 0, 3L, "broken", "not-json");
        record.headers().add(TicketRushProducer.RESERVATION_ID_HEADER,
                "3001-victim".getBytes(StandardCharsets.UTF_8));

        consumer.recover(record);

        assertThat(release.calls).isZero();
    }

    @Test
    void nullPayloadRequiresManualReviewInsteadOfRetryingForever() {
        RushReservation durable = durableReservation("3001-victim");
        FakeReleaseService release = new FakeReleaseService(true);
        TicketRushDltRecoveryConsumer consumer = new TicketRushDltRecoveryConsumer(
                new FakeReservationStore(), new FakeLedger(durable), release, new ObjectMapper());
        ConsumerRecord<String, String> record = new ConsumerRecord<>(
                TicketRushProducer.TOPIC + ".DLT", 0, 4L, "broken", null);
        record.headers().add(TicketRushProducer.RESERVATION_ID_HEADER,
                "3001-victim".getBytes(StandardCharsets.UTF_8));

        consumer.recover(record);

        assertThat(release.calls).isZero();
    }

    @Test
    void nullHeaderValueRequiresManualReviewWithoutLedgerLookup() {
        FakeReleaseService release = new FakeReleaseService(true);
        TicketRushDltRecoveryConsumer consumer = new TicketRushDltRecoveryConsumer(
                new FakeReservationStore(), new FakeLedger(durableReservation("3001-victim")),
                release, new ObjectMapper());
        ConsumerRecord<String, String> record = new ConsumerRecord<>(
                TicketRushProducer.TOPIC + ".DLT", 0, 5L, "broken", "{}");
        record.headers().add(TicketRushProducer.RESERVATION_ID_HEADER, (byte[]) null);

        consumer.recover(record);

        assertThat(release.calls).isZero();
    }

    private static RushReservation durableReservation(String reservationId) {
        return RushReservation.builder()
                .reservationId(reservationId)
                .userId(7L)
                .eventId(20L)
                .skuId(3001L)
                .quantity(1)
                .unitPrice(38000L)
                .status(ReservationStatus.QUEUED.code())
                .build();
    }

    private static String json(TicketRushMessage message) {
        try {
            return new ObjectMapper().writeValueAsString(message);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static final class FakeReleaseService implements InventoryReleaseService {
        private final boolean scheduled;
        private int calls;

        private FakeReleaseService(boolean scheduled) {
            this.scheduled = scheduled;
        }

        @Override
        public boolean scheduleBeforeOrder(String reservationId, Long userId, Long skuId, String reason) {
            calls++;
            return scheduled;
        }

        @Override
        public void scheduleAfterOrder(
                String reservationId, Long orderId, Long userId, Long skuId, String reason) {
            throw new UnsupportedOperationException();
        }

        @Override public void processPending() {}
        @Override public boolean hasUnsettledForSku(Long skuId) { return false; }
        @Override public boolean requeueFailed(String reservationId) { return false; }
    }

    private static final class FakeLedger implements RushReservationLedger {
        private final RushReservation reservation;

        private FakeLedger(RushReservation reservation) {
            this.reservation = reservation;
        }

        @Override public RushReservation recordReserved(ReservationOutboxRecord record) { throw new UnsupportedOperationException(); }
        @Override public Optional<RushReservation> find(String reservationId) { return Optional.of(reservation); }
        @Override public List<RushReservation> findReservedBefore(LocalDateTime cutoff, int limit) { return List.of(); }
        @Override public RushReservation requireMatching(TicketRushMessage message) {
            if (!reservation.getReservationId().equals(message.reservationId())
                    || !reservation.getUserId().equals(message.userId())
                    || !reservation.getEventId().equals(message.eventId())
                    || !reservation.getSkuId().equals(message.skuId())
                    || !reservation.getQuantity().equals(message.quantity())
                    || !reservation.getUnitPrice().equals(message.unitPrice())) {
                throw new IllegalArgumentException("mismatch");
            }
            return reservation;
        }
        @Override public void markQueued(String reservationId) {}
        @Override public boolean tryMarkOrderCreated(String reservationId, Long orderId) { return false; }
        @Override public boolean tryMarkPaid(String reservationId) { return false; }
        @Override public boolean tryMarkReleasePendingBeforeOrder(String reservationId, String reason) { return false; }
        @Override public boolean tryMarkReleasePendingAfterOrder(String reservationId, String reason) { return false; }
        @Override public void markReleased(String reservationId) {}
        @Override public long countBySkuAndStatuses(Long skuId, Collection<Integer> statuses) { return 0; }
    }

    private static final class FakeReservationStore implements RushReservationStore {
        private boolean rejected;

        @Override public boolean initializeSku(TicketSku sku, int availableStock) { return false; }
        @Override public Integer getAvailableStock(Long skuId) { return null; }
        @Override public long getConsumedReservationCount(Long skuId) { return 0; }
        @Override public boolean hasBuyerState(Long skuId) { return false; }
        @Override public ReservationDecision reserve(Long userId, Long eventId, Long skuId, Integer quantity,
                                                     String reservationId, String idempotencyHash) {
            throw new UnsupportedOperationException();
        }
        @Override public Optional<ReservationSnapshot> find(String reservationId) { return Optional.empty(); }
        @Override public void markQueued(String reservationId) {}
        @Override public void markOrderCreated(String reservationId, Long orderId) {}
        @Override public void markPaid(String reservationId) {}
        @Override public void markRejected(String reservationId, String reason) { rejected = true; }
    }
}
