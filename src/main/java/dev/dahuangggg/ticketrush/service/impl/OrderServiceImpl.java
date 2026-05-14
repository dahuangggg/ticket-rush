package dev.dahuangggg.ticketrush.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import dev.dahuangggg.ticketrush.dto.order.OrderDTO;
import dev.dahuangggg.ticketrush.entity.TicketOrder;
import dev.dahuangggg.ticketrush.entity.TicketOrderMsg;
import dev.dahuangggg.ticketrush.entity.TicketSku;
import dev.dahuangggg.ticketrush.exception.OrderNotFoundException;
import dev.dahuangggg.ticketrush.infrastructure.mq.TicketRushMessage;
import dev.dahuangggg.ticketrush.mapper.TicketOrderMapper;
import dev.dahuangggg.ticketrush.mapper.TicketOrderMsgMapper;
import dev.dahuangggg.ticketrush.mapper.TicketSkuMapper;
import dev.dahuangggg.ticketrush.service.OrderService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
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
    private final TicketOrderMsgMapper ticketOrderMsgMapper;
    private final TicketSkuMapper ticketSkuMapper;

    public OrderServiceImpl(TicketOrderMapper ticketOrderMapper,
                            TicketOrderMsgMapper ticketOrderMsgMapper,
                            TicketSkuMapper ticketSkuMapper) {
        this.ticketOrderMapper = ticketOrderMapper;
        this.ticketOrderMsgMapper = ticketOrderMsgMapper;
        this.ticketSkuMapper = ticketSkuMapper;
    }

    @Override
    @Transactional
    public void createOrder(TicketRushMessage message) {
        // 插入消息追踪记录作为幂等门卫
        // messageId 唯一索引冲突（DuplicateKeyException）说明该消息已处理，直接跳过
        TicketOrderMsg orderMsg = TicketOrderMsg.builder()
                .messageId(message.messageId())
                .userId(message.userId())
                .eventId(message.eventId())
                .skuId(message.skuId())
                .quantity(message.quantity())
                .status(0)
                .build();
        try {
            ticketOrderMsgMapper.insert(orderMsg);
        } catch (DuplicateKeyException e) {
            log.info("消息已处理，跳过创单: messageId={}", message.messageId());
            return;
        }

        // 查询票档单价，计算订单总金额（单位分）
        TicketSku sku = ticketSkuMapper.selectById(message.skuId());
        if (sku == null) {
            // 票档不存在时，将消息标记为失败，避免 Kafka 因异常无限重试
            log.error("票档不存在，跳过创单: skuId={}", message.skuId());
            ticketOrderMsgMapper.updateById(
                    TicketOrderMsg.builder().id(orderMsg.getId()).status(2)
                            .errorMessage("票档不存在: " + message.skuId()).build()
            );
            return;
        }
        long totalAmount = sku.getPrice() * message.quantity();

        // 创建待支付订单，orderNo 用 UUID 保证全局唯一
        TicketOrder order = TicketOrder.builder()
                .orderNo(UUID.randomUUID().toString().replace("-", ""))
                .userId(message.userId())
                .eventId(message.eventId())
                .skuId(message.skuId())
                .quantity(message.quantity())
                .totalAmount(totalAmount)
                .status(0)
                .build();
        // 上游 Lua 脚本已通过 SADD ticket:order:user:{skuId} 保证同一 (userId, skuId) 只发送一条消息，
        // uk_user_sku 唯一键在正常流程下不会冲突，此处作为最终兜底。
        // 注意：若此处抛出 DuplicateKeyException，整个事务（含消息追踪记录的 INSERT）将被回滚，
        // Kafka 会重试该消息，但因消息记录也被回滚，messageId 幂等门卫将在下次重试时再次触发，
        // 导致无限重试循环。生产环境建议将消息追踪记录的写入改为 REQUIRES_NEW 传播，
        // 使其在独立事务中提交，确保 messageId 门卫在重试时生效。
        ticketOrderMapper.insert(order);

        // 标记消息处理成功（updateById 仅更新非 null 字段，其余字段保持不变）
        ticketOrderMsgMapper.updateById(
                TicketOrderMsg.builder().id(orderMsg.getId()).status(1).build()
        );
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
