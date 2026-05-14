package dev.dahuangggg.ticketrush.service;

import dev.dahuangggg.ticketrush.dto.rush.TicketRushRequest;
import dev.dahuangggg.ticketrush.dto.rush.TicketRushResponse;

public interface TicketRushService {

    /**
     * 执行抢票流程：Redis Lua 校验库存和去重，成功后发送 Kafka 消息。
     * 失败时抛出 SoldOutException 或 DuplicateOrderException。
     */
    TicketRushResponse rush(Long userId, TicketRushRequest request);
}
