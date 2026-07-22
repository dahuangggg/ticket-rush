package dev.dahuangggg.ticketrush.infrastructure.mq;

import dev.dahuangggg.ticketrush.domain.rush.ReservationOutboxRecord;
import dev.dahuangggg.ticketrush.domain.rush.ReservationStatus;
import dev.dahuangggg.ticketrush.entity.RushReservation;
import dev.dahuangggg.ticketrush.infrastructure.redis.RedisKeyRegistry;
import dev.dahuangggg.ticketrush.service.ReservationOutbox;
import dev.dahuangggg.ticketrush.service.RushReservationLedger;
import dev.dahuangggg.ticketrush.service.RushReservationStore;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.time.LocalDateTime;
import java.time.Clock;

/**
 * Redis Outbox 到 Kafka 的可靠 Relay Adapter。
 *
 * <p>Kafka 超时属于结果未知：记录不会删除，下一轮会使用相同 Reservation ID 重发。
 * Order Intake 的 inbox 唯一键负责吸收重复消息。</p>
 */
@Component
public class ReservationOutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(ReservationOutboxRelay.class);

    private final ReservationOutbox outbox;
    private final RushReservationLedger ledger;
    private final RushReservationStore reservationStore;
    private final TicketRushProducer producer;
    private final RedissonClient redissonClient;
    private final int batchSize;
    private final long durableRecoveryAgeSeconds;
    private final Clock clock;

    public ReservationOutboxRelay(ReservationOutbox outbox,
                                  RushReservationLedger ledger,
                                  RushReservationStore reservationStore,
                                  TicketRushProducer producer,
                                  Optional<RedissonClient> redissonClient,
                                  @Value("${ticket-rush.reservation-relay.batch-size:100}") int batchSize,
                                  @Value("${ticket-rush.reservation-relay.durable-recovery-age-seconds:5}")
                                  long durableRecoveryAgeSeconds,
                                  Clock clock) {
        this.outbox = outbox;
        this.ledger = ledger;
        this.reservationStore = reservationStore;
        this.producer = producer;
        this.redissonClient = redissonClient.orElse(null);
        this.batchSize = batchSize;
        this.durableRecoveryAgeSeconds = durableRecoveryAgeSeconds;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${ticket-rush.reservation-relay.fixed-delay-ms:200}")
    public void relay() {
        try {
            for (Long skuId : outbox.registeredSkuIds()) {
                try {
                    relaySkuWithOptionalLock(skuId);
                } catch (RuntimeException e) {
                    log.error("Reservation relay SKU scan failed; other SKUs will continue: skuId={}",
                            skuId, e);
                }
            }
        } catch (RuntimeException e) {
            log.error("Reservation relay registry scan failed; durable recovery will still run", e);
        }
        try {
            relayDurableOrphans();
        } catch (RuntimeException e) {
            log.error("Durable reservation recovery scan failed", e);
        }
    }

    private void relaySkuWithOptionalLock(Long skuId) {
        if (redissonClient == null) {
            relaySku(skuId);
            return;
        }
        RLock lock = redissonClient.getLock(RedisKeyRegistry.reservationOutboxRelayLock(skuId));
        if (!lock.tryLock()) {
            return;
        }
        try {
            relaySku(skuId);
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    private void relaySku(Long skuId) {
        for (ReservationOutboxRecord record : outbox.pending(skuId, batchSize)) {
            try {
                // 先把 Reservation 持久化，再允许消息进入 Kafka。
                ledger.recordReserved(record);
                producer.send(new TicketRushMessage(
                        record.reservationId(),
                        record.reservationId(),
                        record.userId(),
                        record.eventId(),
                        record.skuId(),
                        record.quantity(),
                        record.unitPrice()
                ));
                ledger.markQueued(record.reservationId());
                reservationStore.markQueued(record.reservationId());
                outbox.acknowledge(skuId, record.streamId());
            } catch (RuntimeException e) {
                // 保留当前及后续 stream entry，下一轮使用相同 ID 重试。
                log.warn("Reservation relay failed; entry retained: reservationId={}",
                        record.reservationId(), e);
                break;
            }
        }
    }

    /**
     * Redis Stream 不是唯一恢复依据：recordReserved 成功后，MySQL Ledger 已经拥有
     * 完整 payload。如果 Stream 在 Kafka ack 前丢失，老 RESERVED 记录会使用同一 ID 重放。
     */
    private void relayDurableOrphans() {
        LocalDateTime cutoff = LocalDateTime.now(clock).minusSeconds(durableRecoveryAgeSeconds);
        for (RushReservation reservation : ledger.findReservedBefore(cutoff, batchSize)) {
            relayDurableWithOptionalLock(reservation);
        }
    }

    private void relayDurableWithOptionalLock(RushReservation reservation) {
        if (redissonClient == null) {
            relayDurable(reservation);
            return;
        }
        RLock lock = redissonClient.getLock(
                RedisKeyRegistry.reservationOutboxRelayLock(reservation.getSkuId()));
        if (!lock.tryLock()) {
            return;
        }
        try {
            relayDurable(reservation);
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    private void relayDurable(RushReservation candidate) {
        RushReservation current = ledger.find(candidate.getReservationId()).orElse(null);
        if (current == null
                || ReservationStatus.fromCode(current.getStatus()) != ReservationStatus.RESERVED) {
            return;
        }
        try {
            producer.send(new TicketRushMessage(
                    current.getReservationId(),
                    current.getReservationId(),
                    current.getUserId(),
                    current.getEventId(),
                    current.getSkuId(),
                    current.getQuantity(),
                    current.getUnitPrice()));
            ledger.markQueued(current.getReservationId());
            reservationStore.markQueued(current.getReservationId());
        } catch (RuntimeException e) {
            log.warn("Durable reservation recovery relay failed: reservationId={}",
                    current.getReservationId(), e);
        }
    }
}
