package dev.dahuangggg.ticketrush.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import dev.dahuangggg.ticketrush.dto.order.OrderDTO;
import dev.dahuangggg.ticketrush.entity.TicketOrder;
import dev.dahuangggg.ticketrush.entity.TicketOrderMsg;
import dev.dahuangggg.ticketrush.entity.TicketSku;
import dev.dahuangggg.ticketrush.exception.OrderNotFoundException;
import dev.dahuangggg.ticketrush.infrastructure.mq.TicketRushMessage;
import dev.dahuangggg.ticketrush.mapper.TicketOrderMapper;
import dev.dahuangggg.ticketrush.mapper.TicketSkuMapper;
import dev.dahuangggg.ticketrush.service.OrderService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * 订单服务实现，负责从 Kafka 消息创建订单，以及订单查询。
 */
@Service
public class OrderServiceImpl implements OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderServiceImpl.class);

    private final TicketOrderMapper ticketOrderMapper;
    private final TicketOrderMsgService ticketOrderMsgService;
    private final TicketSkuMapper ticketSkuMapper;

    public OrderServiceImpl(TicketOrderMapper ticketOrderMapper,
                            TicketOrderMsgService ticketOrderMsgService,
                            TicketSkuMapper ticketSkuMapper) {
        this.ticketOrderMapper = ticketOrderMapper;
        this.ticketOrderMsgService = ticketOrderMsgService;
        this.ticketSkuMapper = ticketSkuMapper;
    }

    @Override
    @Transactional
    public void createOrder(TicketRushMessage message) {
        // 在独立事务中插入消息追踪记录（幂等门卫）
        TicketOrderMsg orderMsg = TicketOrderMsg.builder()
                .messageId(message.messageId())
                .userId(message.userId())
                .eventId(message.eventId())
                .skuId(message.skuId())
                .quantity(message.quantity())
                .status(TicketOrderMsg.STATUS_PENDING)
                .build();
        TicketOrderMsg inserted = ticketOrderMsgService.insertIfAbsent(orderMsg);
        if (inserted == null) {
            log.info("消息已处理，跳过创单: messageId={}", message.messageId());
            return;
        }

        TicketSku sku = ticketSkuMapper.selectById(message.skuId());
        if (sku == null) {
            log.error("票档不存在，跳过创单: skuId={}", message.skuId());
            ticketOrderMsgService.markFailed(inserted.getId(), "票档不存在: " + message.skuId());
            return;
        }
        long totalAmount = sku.getPrice() * message.quantity();

        TicketOrder order = TicketOrder.builder()
                .orderNo(UUID.randomUUID().toString().replace("-", ""))
                .userId(message.userId())
                .eventId(message.eventId())
                .skuId(message.skuId())
                .quantity(message.quantity())
                .totalAmount(totalAmount)
                .status(TicketOrder.STATUS_PENDING)
                .build();
        ticketOrderMapper.insert(order);

        ticketOrderMsgService.markSuccess(inserted.getId());
        log.info("订单创建成功: orderId={} userId={} skuId={}", order.getId(), message.userId(), message.skuId());
    }

    @Override
    public OrderDTO getById(Long orderId, Long userId) {
        TicketOrder order = ticketOrderMapper.selectById(orderId);
        if (order == null || !order.getUserId().equals(userId)) {
            throw new OrderNotFoundException(orderId);
        }
        return toDTO(order);
    }

    @Override
    public OrderDTO getByOrderNo(String orderNo, Long userId) {
        TicketOrder order = ticketOrderMapper.selectOne(
                new LambdaQueryWrapper<TicketOrder>().eq(TicketOrder::getOrderNo, orderNo)
        );
        if (order == null || !order.getUserId().equals(userId)) {
            throw new OrderNotFoundException(orderNo);
        }
        return toDTO(order);
    }

    @Override
    public List<OrderDTO> listByUser(Long userId) {
        return ticketOrderMapper.selectList(
                new LambdaQueryWrapper<TicketOrder>()
                        .eq(TicketOrder::getUserId, userId)
                        .orderByDesc(TicketOrder::getCreateTime)
        ).stream().map(this::toDTO).toList();
    }

    /**
     * 将订单实体转换为 DTO，不暴露内部字段（如 deleted、updateTime）。
     */
    private OrderDTO toDTO(TicketOrder order) {
        return new OrderDTO(
                order.getId(),
                order.getOrderNo(),
                order.getEventId(),
                order.getSkuId(),
                order.getQuantity(),
                order.getTotalAmount(),
                order.getStatus(),
                order.getCreateTime(),
                order.getPayTime(),
                order.getCancelTime()
        );
    }
}
