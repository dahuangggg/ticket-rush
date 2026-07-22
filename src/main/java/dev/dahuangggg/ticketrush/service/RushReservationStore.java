package dev.dahuangggg.ticketrush.service;

import dev.dahuangggg.ticketrush.domain.rush.ReservationDecision;
import dev.dahuangggg.ticketrush.domain.rush.ReservationSnapshot;
import dev.dahuangggg.ticketrush.entity.TicketSku;

import java.util.Optional;

/**
 * Rush Reservation Module 的 Redis seam。
 *
 * <p>Interface 隐藏 Lua、Redis TIME、Cluster 共槽 key 和固定过期时间等 Implementation 细节。</p>
 */
public interface RushReservationStore {

    boolean initializeSku(TicketSku sku, int availableStock);

    Integer getAvailableStock(Long skuId);

    /**
     * Redis buyers set 中当前仍占用库存的用户数。它包含尚未 relay 到 MySQL 的 Reservation。
     */
    long getConsumedReservationCount(Long skuId);

    /** buyers key 是否仍存在；用于区分“仅库存键丢失”和“Redis 全量证据丢失”。 */
    boolean hasBuyerState(Long skuId);

    ReservationDecision reserve(Long userId, Long eventId, Long skuId, Integer quantity,
                                String reservationId, String idempotencyHash);

    Optional<ReservationSnapshot> find(String reservationId);

    void markQueued(String reservationId);

    void markOrderCreated(String reservationId, Long orderId);

    void markPaid(String reservationId);

    void markRejected(String reservationId, String reason);
}
