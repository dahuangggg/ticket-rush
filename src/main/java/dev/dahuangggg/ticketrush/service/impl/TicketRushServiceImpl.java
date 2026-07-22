package dev.dahuangggg.ticketrush.service.impl;

import dev.dahuangggg.ticketrush.domain.rush.ReservationDecision;
import dev.dahuangggg.ticketrush.domain.rush.ReservationSnapshot;
import dev.dahuangggg.ticketrush.domain.rush.ReservationStatus;
import dev.dahuangggg.ticketrush.dto.rush.TicketRushRequest;
import dev.dahuangggg.ticketrush.dto.rush.TicketRushResponse;
import dev.dahuangggg.ticketrush.entity.RushReservation;
import dev.dahuangggg.ticketrush.exception.DuplicateOrderException;
import dev.dahuangggg.ticketrush.exception.ReservationNotFoundException;
import dev.dahuangggg.ticketrush.exception.SoldOutException;
import dev.dahuangggg.ticketrush.exception.TicketSkuUnavailableException;
import dev.dahuangggg.ticketrush.service.RushReservationLedger;
import dev.dahuangggg.ticketrush.service.RushReservationStore;
import dev.dahuangggg.ticketrush.service.TicketRushService;
import dev.dahuangggg.ticketrush.infrastructure.observability.TicketRushMetrics;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Rush Admission Module。
 *
 * <p>HTTP 只需要知道“提交一次带幂等键的请求”；Redis Lua、Cluster key、Outbox 和状态查询
 * 全部隐藏在后面的 Adapter 中。</p>
 */
@Service
public class TicketRushServiceImpl implements TicketRushService {

    private static final int MAX_IDEMPOTENCY_KEY_LENGTH = 128;

    private final RushReservationStore reservationStore;
    private final RushReservationLedger reservationLedger;
    private final TicketRushMetrics metrics;

    public TicketRushServiceImpl(RushReservationStore reservationStore,
                                 RushReservationLedger reservationLedger,
                                 TicketRushMetrics metrics) {
        this.reservationStore = reservationStore;
        this.reservationLedger = reservationLedger;
        this.metrics = metrics;
    }

    @Override
    public TicketRushResponse rush(Long userId, TicketRushRequest request, String idempotencyKey) {
        String normalizedKey = normalizeIdempotencyKey(idempotencyKey);
        String reservationId = request.skuId() + "-" + UUID.randomUUID().toString().replace("-", "");
        ReservationDecision decision = reservationStore.reserve(
                userId,
                request.eventId(),
                request.skuId(),
                request.quantity(),
                reservationId,
                sha256(normalizedKey)
        );
        metrics.recordRushOutcome(switch (decision.type()) {
            case RESERVED -> TicketRushMetrics.RushOutcome.RESERVED;
            case EXISTING -> TicketRushMetrics.RushOutcome.IDEMPOTENT_REPLAY;
            case SOLD_OUT -> TicketRushMetrics.RushOutcome.SOLD_OUT;
            case DUPLICATE_USER -> TicketRushMetrics.RushOutcome.DUPLICATE;
            case SKU_UNAVAILABLE -> TicketRushMetrics.RushOutcome.UNAVAILABLE;
        });

        return switch (decision.type()) {
            case RESERVED -> new TicketRushResponse(
                    decision.reservationId(), ReservationStatus.RESERVED.name(), null);
            case EXISTING -> getReservation(decision.reservationId(), userId);
            case SOLD_OUT -> throw new SoldOutException(request.skuId());
            case DUPLICATE_USER -> throw new DuplicateOrderException(request.skuId());
            case SKU_UNAVAILABLE -> throw new TicketSkuUnavailableException(request.skuId());
        };
    }

    @Override
    public TicketRushResponse getReservation(String reservationId, Long userId) {
        RushReservation durable = reservationLedger.find(reservationId).orElse(null);
        if (durable != null) {
            requireOwner(durable.getUserId(), userId, reservationId);
            return new TicketRushResponse(
                    reservationId,
                    ReservationStatus.fromCode(durable.getStatus()).name(),
                    durable.getOrderId());
        }

        // Relay 尚未把新 Reservation 写入 MySQL 时，Redis 是唯一可见状态。
        ReservationSnapshot redis = reservationStore.find(reservationId)
                .orElseThrow(() -> new ReservationNotFoundException(reservationId));
        requireOwner(redis.userId(), userId, reservationId);
        return new TicketRushResponse(reservationId, redis.status().name(), redis.orderId());
    }

    private void requireOwner(Long ownerId, Long currentUserId, String reservationId) {
        if (!ownerId.equals(currentUserId)) {
            // 与订单查询一致：不向调用者泄露其他用户的资源是否存在。
            throw new ReservationNotFoundException(reservationId);
        }
    }

    private String normalizeIdempotencyKey(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Idempotency-Key must not be blank");
        }
        String normalized = value.trim();
        if (normalized.length() > MAX_IDEMPOTENCY_KEY_LENGTH) {
            throw new IllegalArgumentException("Idempotency-Key must not exceed 128 characters");
        }
        return normalized;
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
