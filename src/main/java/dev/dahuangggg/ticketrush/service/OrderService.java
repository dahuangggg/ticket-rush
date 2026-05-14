package dev.dahuangggg.ticketrush.service;

import dev.dahuangggg.ticketrush.dto.order.OrderDTO;
import dev.dahuangggg.ticketrush.infrastructure.mq.TicketRushMessage;

import java.util.List;

/**
 * 订单服务接口，提供订单创建（由 Kafka 消费者调用）和查询能力。
 */
public interface OrderService {

    /** 由 Kafka 消费者调用，原子创建订单（含幂等保护）。 */
    void createOrder(TicketRushMessage message);

    /** 按订单 ID 查询，仅返回属于 userId 的订单，否则抛出 OrderNotFoundException。 */
    OrderDTO getById(Long orderId, Long userId);

    /** 按业务订单号查询，仅返回属于 userId 的订单，否则抛出 OrderNotFoundException。 */
    OrderDTO getByOrderNo(String orderNo, Long userId);

    /** 查询当前用户的所有订单，按创建时间倒序。 */
    List<OrderDTO> listByUser(Long userId);
}
