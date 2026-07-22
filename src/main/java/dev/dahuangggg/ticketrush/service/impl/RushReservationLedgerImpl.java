package dev.dahuangggg.ticketrush.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import dev.dahuangggg.ticketrush.domain.rush.ReservationOutboxRecord;
import dev.dahuangggg.ticketrush.domain.rush.ReservationStatus;
import dev.dahuangggg.ticketrush.entity.RushReservation;
import dev.dahuangggg.ticketrush.infrastructure.mq.TicketRushMessage;
import dev.dahuangggg.ticketrush.mapper.RushReservationMapper;
import dev.dahuangggg.ticketrush.service.RushReservationLedger;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Service
public class RushReservationLedgerImpl implements RushReservationLedger {

    private final RushReservationMapper mapper;

    public RushReservationLedgerImpl(RushReservationMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public RushReservation recordReserved(ReservationOutboxRecord record) {
        RushReservation existing = select(record.reservationId());
        if (existing != null) {
            assertMatches(existing, record.userId(), record.eventId(), record.skuId(),
                    record.quantity(), record.unitPrice());
            return existing;
        }
        RushReservation reservation = RushReservation.builder()
                .reservationId(record.reservationId())
                .userId(record.userId())
                .eventId(record.eventId())
                .skuId(record.skuId())
                .quantity(record.quantity())
                .unitPrice(record.unitPrice())
                .status(ReservationStatus.RESERVED.code())
                .build();
        try {
            mapper.insert(reservation);
            return reservation;
        } catch (DuplicateKeyException e) {
            RushReservation raced = select(record.reservationId());
            if (raced == null) {
                throw e;
            }
            assertMatches(raced, record.userId(), record.eventId(), record.skuId(),
                    record.quantity(), record.unitPrice());
            return raced;
        }
    }

    @Override
    public Optional<RushReservation> find(String reservationId) {
        return Optional.ofNullable(select(reservationId));
    }

    @Override
    public List<RushReservation> findReservedBefore(LocalDateTime cutoff, int limit) {
        return mapper.selectList(new LambdaQueryWrapper<RushReservation>()
                .eq(RushReservation::getStatus, ReservationStatus.RESERVED.code())
                .le(RushReservation::getCreateTime, cutoff)
                .orderByAsc(RushReservation::getCreateTime)
                .orderByAsc(RushReservation::getId)
                .last("LIMIT " + Math.max(1, limit)));
    }

    @Override
    public RushReservation requireMatching(TicketRushMessage message) {
        RushReservation reservation = select(message.reservationId());
        if (reservation == null) {
            throw new IllegalArgumentException("Reservation ledger entry not found: " + message.reservationId());
        }
        assertMatches(reservation, message.userId(), message.eventId(), message.skuId(),
                message.quantity(), message.unitPrice());
        ReservationStatus status = ReservationStatus.fromCode(reservation.getStatus());
        if (status != ReservationStatus.RESERVED
                && status != ReservationStatus.QUEUED
                && status != ReservationStatus.ORDER_CREATED) {
            throw new IllegalArgumentException(
                    "Reservation is not orderable: " + message.reservationId() + " status=" + status);
        }
        return reservation;
    }

    @Override
    public void markQueued(String reservationId) {
        transition(reservationId, List.of(ReservationStatus.RESERVED), ReservationStatus.QUEUED, null, null);
    }

    @Override
    public boolean tryMarkOrderCreated(String reservationId, Long orderId) {
        int updated = transition(reservationId,
                List.of(ReservationStatus.RESERVED, ReservationStatus.QUEUED),
                ReservationStatus.ORDER_CREATED,
                orderId,
                null);
        if (updated == 1) {
            return true;
        }
        RushReservation existing = select(reservationId);
        return existing != null
                && ReservationStatus.fromCode(existing.getStatus()) == ReservationStatus.ORDER_CREATED
                && orderId.equals(existing.getOrderId());
    }

    @Override
    public boolean tryMarkPaid(String reservationId) {
        int updated = transition(reservationId,
                List.of(ReservationStatus.ORDER_CREATED), ReservationStatus.PAID, null, null);
        if (updated == 1) {
            return true;
        }
        RushReservation existing = select(reservationId);
        return existing != null
                && ReservationStatus.fromCode(existing.getStatus()) == ReservationStatus.PAID;
    }

    @Override
    public boolean tryMarkReleasePendingBeforeOrder(String reservationId, String reason) {
        return transition(reservationId,
                List.of(ReservationStatus.RESERVED, ReservationStatus.QUEUED),
                ReservationStatus.RELEASE_PENDING, null, reason) == 1;
    }

    @Override
    public boolean tryMarkReleasePendingAfterOrder(String reservationId, String reason) {
        return transition(reservationId,
                List.of(ReservationStatus.ORDER_CREATED),
                ReservationStatus.RELEASE_PENDING, null, reason) == 1;
    }

    @Override
    public void markReleased(String reservationId) {
        transition(reservationId,
                List.of(ReservationStatus.RELEASE_PENDING, ReservationStatus.REJECTED),
                ReservationStatus.RELEASED,
                null,
                null);
    }

    @Override
    public long countBySkuAndStatuses(Long skuId, Collection<Integer> statuses) {
        return mapper.selectCount(new LambdaQueryWrapper<RushReservation>()
                .eq(RushReservation::getSkuId, skuId)
                .in(RushReservation::getStatus, statuses));
    }

    private RushReservation select(String reservationId) {
        return mapper.selectOne(new LambdaQueryWrapper<RushReservation>()
                .eq(RushReservation::getReservationId, reservationId));
    }

    private int transition(String reservationId, Collection<ReservationStatus> from,
                           ReservationStatus to, Long orderId, String reason) {
        LambdaUpdateWrapper<RushReservation> update = new LambdaUpdateWrapper<RushReservation>()
                .eq(RushReservation::getReservationId, reservationId)
                .in(RushReservation::getStatus, from.stream().map(ReservationStatus::code).toList())
                .set(RushReservation::getStatus, to.code());
        if (orderId != null) {
            update.set(RushReservation::getOrderId, orderId);
        }
        if (reason != null) {
            update.set(RushReservation::getRejectReason, truncate(reason));
        }
        return mapper.update(null, update);
    }

    private void assertMatches(RushReservation reservation, Long userId, Long eventId, Long skuId,
                               Integer quantity, Long unitPrice) {
        if (!reservation.getUserId().equals(userId)
                || !reservation.getEventId().equals(eventId)
                || !reservation.getSkuId().equals(skuId)
                || !reservation.getQuantity().equals(quantity)
                || !reservation.getUnitPrice().equals(unitPrice)) {
            throw new IllegalArgumentException(
                    "Reservation payload does not match ledger: " + reservation.getReservationId());
        }
    }

    private String truncate(String value) {
        return value == null || value.length() <= 1024 ? value : value.substring(0, 1024);
    }
}
