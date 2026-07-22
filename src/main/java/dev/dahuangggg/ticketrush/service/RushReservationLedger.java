package dev.dahuangggg.ticketrush.service;

import dev.dahuangggg.ticketrush.domain.rush.ReservationOutboxRecord;
import dev.dahuangggg.ticketrush.entity.RushReservation;
import dev.dahuangggg.ticketrush.infrastructure.mq.TicketRushMessage;

import java.util.Collection;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * MySQL Reservation Ledger Module。
 * 调用方只需要表达生命周期变化；条件更新和状态合法性留在 Implementation。
 */
public interface RushReservationLedger {

    RushReservation recordReserved(ReservationOutboxRecord record);

    Optional<RushReservation> find(String reservationId);

    /** Redis journal 丢失时，Relay 可从已持久化的老 RESERVED 记录恢复发布。 */
    List<RushReservation> findReservedBefore(LocalDateTime cutoff, int limit);

    RushReservation requireMatching(TicketRushMessage message);

    void markQueued(String reservationId);

    /** Order Intake 与 DLT 释放竞争时的 CAS；false 表示另一个终态已获胜。 */
    boolean tryMarkOrderCreated(String reservationId, Long orderId);

    /** 支付与释放竞争时的 CAS。 */
    boolean tryMarkPaid(String reservationId);

    boolean tryMarkReleasePendingBeforeOrder(String reservationId, String reason);

    boolean tryMarkReleasePendingAfterOrder(String reservationId, String reason);

    void markReleased(String reservationId);

    long countBySkuAndStatuses(Long skuId, Collection<Integer> statuses);
}
