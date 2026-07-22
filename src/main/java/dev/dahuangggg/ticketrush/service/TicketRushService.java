package dev.dahuangggg.ticketrush.service;

import dev.dahuangggg.ticketrush.dto.rush.TicketRushRequest;
import dev.dahuangggg.ticketrush.dto.rush.TicketRushResponse;

public interface TicketRushService {

    /**
     * 在 Redis 线性化点原子完成 eligibility、去重、库存预占与 Reservation Outbox 写入。
     * 此方法不直接等待 Kafka，成功表示 Reservation 已被系统接纳。
     */
    TicketRushResponse rush(Long userId, TicketRushRequest request, String idempotencyKey);

    /** 查询属于当前用户的 Reservation 状态。 */
    TicketRushResponse getReservation(String reservationId, Long userId);
}
