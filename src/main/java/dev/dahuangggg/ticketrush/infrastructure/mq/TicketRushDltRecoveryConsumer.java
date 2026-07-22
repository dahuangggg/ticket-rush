package dev.dahuangggg.ticketrush.infrastructure.mq;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.dahuangggg.ticketrush.domain.rush.ReservationStatus;
import dev.dahuangggg.ticketrush.entity.RushReservation;
import dev.dahuangggg.ticketrush.service.InventoryReleaseService;
import dev.dahuangggg.ticketrush.service.RushReservationLedger;
import dev.dahuangggg.ticketrush.service.RushReservationStore;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * ticket.rush.requests.DLT 的恢复 Adapter。
 * 只有 header、payload 与 MySQL Ledger 完整匹配时才自动创建 Release Intent；损坏或不一致的
 * payload 保留在 DLT 供人工审查，不能仅凭 header 释放库存。
 */
@Component
public class TicketRushDltRecoveryConsumer {

    private static final Logger log = LoggerFactory.getLogger(TicketRushDltRecoveryConsumer.class);

    private final RushReservationStore reservationStore;
    private final RushReservationLedger ledger;
    private final InventoryReleaseService releaseService;
    private final ObjectMapper objectMapper;

    public TicketRushDltRecoveryConsumer(RushReservationStore reservationStore,
                                         RushReservationLedger ledger,
                                         InventoryReleaseService releaseService,
                                         ObjectMapper objectMapper) {
        this.reservationStore = reservationStore;
        this.ledger = ledger;
        this.releaseService = releaseService;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(
            topics = TicketRushProducer.TOPIC + ".DLT",
            groupId = "${spring.kafka.consumer.group-id}-dlt-recovery",
            containerFactory = "dltRecoveryKafkaListenerContainerFactory")
    public void recover(ConsumerRecord<String, String> record) {
        Header header = record.headers().lastHeader(TicketRushProducer.RESERVATION_ID_HEADER);
        if (header == null || header.value() == null || header.value().length == 0) {
            log.error("Rush DLT record has no usable reservationId header: partition={} offset={}",
                    record.partition(), record.offset());
            return;
        }
        String reservationId = new String(header.value(), StandardCharsets.UTF_8);
        if (reservationId.isBlank()) {
            log.error("Rush DLT record has a blank reservationId header: partition={} offset={}",
                    record.partition(), record.offset());
            return;
        }
        RushReservation durable = ledger.find(reservationId).orElse(null);
        if (durable == null) {
            log.error("Rush DLT has no durable reservation; manual review required: reservationId={}",
                    reservationId);
            return;
        }

        String rawPayload = record.value();
        if (rawPayload == null || rawPayload.isBlank()) {
            log.error("Rush DLT payload is empty; manual review required: reservationId={}",
                    reservationId);
            return;
        }

        TicketRushMessage message;
        try {
            message = objectMapper.readValue(rawPayload, TicketRushMessage.class);
        } catch (JsonProcessingException e) {
            // Header alone is not authenticated business evidence. Keep the raw Kafka DLT record
            // for manual review instead of releasing a possibly unrelated Reservation.
            log.error("Rush DLT payload cannot be authenticated; manual review required: reservationId={}",
                    reservationId, e);
            return;
        }
        if (!reservationId.equals(message.reservationId())
                || !reservationId.equals(message.messageId())) {
            log.error("Rush DLT identity mismatch; manual review required: header={} payloadReservation={} messageId={}",
                    reservationId, message.reservationId(), message.messageId());
            return;
        }

        ReservationStatus status = ReservationStatus.fromCode(durable.getStatus());
        if (status == ReservationStatus.ORDER_CREATED || status == ReservationStatus.PAID) {
            log.warn("Rush DLT ignored because durable order state already exists: reservationId={} status={}",
                    reservationId, status);
            return;
        }
        if (status == ReservationStatus.RELEASE_PENDING || status == ReservationStatus.RELEASED) {
            return;
        }
        try {
            ledger.requireMatching(message);
        } catch (IllegalArgumentException e) {
            log.error("Rush DLT payload does not match durable reservation; manual review required: reservationId={}",
                    reservationId, e);
            return;
        }
        if (!releaseService.scheduleBeforeOrder(
                reservationId, durable.getUserId(), durable.getSkuId(), "KAFKA_DLT")) {
            // 条件状态转换与 Order Intake 共享同一行锁；返回 false 表示创单/支付已经获胜。
            log.warn("Rush DLT release skipped after concurrent state change: reservationId={}",
                    reservationId);
            return;
        }
        try {
            reservationStore.markRejected(reservationId, "KAFKA_DLT");
        } catch (RuntimeException e) {
            // Durable Release Intent 已提交；Redis 恢复后 worker 会继续幂等释放。
            log.warn("DLT release scheduled but Redis status update failed: reservationId={}",
                    reservationId, e);
        }
        log.warn("Rush DLT converted to Release Intent: reservationId={}", reservationId);
    }
}
