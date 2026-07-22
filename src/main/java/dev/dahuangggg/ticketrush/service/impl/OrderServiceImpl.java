package dev.dahuangggg.ticketrush.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import dev.dahuangggg.ticketrush.domain.order.OrderCreationResult;
import dev.dahuangggg.ticketrush.dto.order.OrderDTO;
import dev.dahuangggg.ticketrush.entity.RushReservation;
import dev.dahuangggg.ticketrush.entity.TicketOrder;
import dev.dahuangggg.ticketrush.entity.TicketOrderMsg;
import dev.dahuangggg.ticketrush.entity.TicketSku;
import dev.dahuangggg.ticketrush.exception.OrderNotFoundException;
import dev.dahuangggg.ticketrush.infrastructure.mq.TicketRushMessage;
import dev.dahuangggg.ticketrush.infrastructure.observability.TicketRushMetrics;
import dev.dahuangggg.ticketrush.mapper.TicketOrderMapper;
import dev.dahuangggg.ticketrush.mapper.TicketOrderMsgMapper;
import dev.dahuangggg.ticketrush.mapper.TicketSkuMapper;
import dev.dahuangggg.ticketrush.service.InventoryReleaseService;
import dev.dahuangggg.ticketrush.service.OrderService;
import dev.dahuangggg.ticketrush.service.RushReservationLedger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.time.Duration;

/**
 * Order Intake Module。
 *
 * <p>inbox、订单和 Reservation Ledger 状态共享一个 MySQL 事务。失败时三者一起回滚，
 * Kafka 才能进行真实重试；永久业务拒绝则提交 FAILED inbox 与 Release Intent。</p>
 */
@Service
public class OrderServiceImpl implements OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderServiceImpl.class);

    private final TicketOrderMapper orderMapper;
    private final TicketOrderMsgMapper messageMapper;
    private final TicketSkuMapper skuMapper;
    private final RushReservationLedger reservationLedger;
    private final InventoryReleaseService releaseService;
    private final TicketRushMetrics metrics;

    public OrderServiceImpl(TicketOrderMapper orderMapper,
                            TicketOrderMsgMapper messageMapper,
                            TicketSkuMapper skuMapper,
                            RushReservationLedger reservationLedger,
                            InventoryReleaseService releaseService,
                            TicketRushMetrics metrics) {
        this.orderMapper = orderMapper;
        this.messageMapper = messageMapper;
        this.skuMapper = skuMapper;
        this.reservationLedger = reservationLedger;
        this.releaseService = releaseService;
        this.metrics = metrics;
    }

    @Override
    @Transactional
    public OrderCreationResult createOrder(TicketRushMessage message) {
        long startedAt = System.nanoTime();
        try {
            OrderCreationResult result = processRushMessage(message);
            metrics.recordOrderCreation(switch (result.status()) {
                case CREATED -> TicketRushMetrics.OrderCreationOutcome.CREATED;
                case ALREADY_CREATED -> TicketRushMetrics.OrderCreationOutcome.ALREADY_CREATED;
                case REJECTED -> TicketRushMetrics.OrderCreationOutcome.REJECTED;
            }, Duration.ofNanos(System.nanoTime() - startedAt));
            return result;
        } catch (RuntimeException e) {
            metrics.recordOrderCreation(TicketRushMetrics.OrderCreationOutcome.FAILED,
                    Duration.ofNanos(System.nanoTime() - startedAt));
            throw e;
        }
    }

    private OrderCreationResult processRushMessage(TicketRushMessage message) {
        // The relay deliberately uses one stable identity for the Reservation, message and inbox.
        // Reject before any inbox lookup: otherwise a forged messageId could consume another
        // Reservation's idempotency slot and make the legitimate message look already processed.
        if (!Objects.equals(message.messageId(), message.reservationId())) {
            throw new IllegalArgumentException(
                    "Kafka message identity does not match Reservation identity");
        }
        TicketOrderMsg inbox = findMessage(message.messageId());
        if (inbox != null) {
            assertInboxMatches(inbox, message);
        }
        if (inbox != null && inbox.getStatus() == TicketOrderMsg.STATUS_SUCCESS) {
            return OrderCreationResult.alreadyCreated(inbox.getOrderId());
        }
        if (inbox != null && inbox.getStatus() == TicketOrderMsg.STATUS_FAILED) {
            return OrderCreationResult.rejected(inbox.getErrorMessage());
        }
        if (inbox == null) {
            inbox = TicketOrderMsg.builder()
                    .messageId(message.messageId())
                    .reservationId(message.reservationId())
                    .userId(message.userId())
                    .eventId(message.eventId())
                    .skuId(message.skuId())
                    .quantity(message.quantity())
                    .status(TicketOrderMsg.STATUS_PENDING)
                    .build();
            // 唯一键竞争时让事务回滚并交给 Kafka 重试；不要把未知重复吞掉。
            messageMapper.insert(inbox);
        }

        // payload/ledger 不匹配是消息完整性故障，不是业务拒绝。让整个事务回滚并进入
        // Kafka retry/DLT，否则一条伪造消息会把 stable messageId 永久毒化为 FAILED。
        RushReservation reservation = reservationLedger.requireMatching(message);

        String invalidReason = validateOrderableSku(message);
        if (invalidReason != null) {
            return reject(inbox, message, invalidReason, true);
        }

        TicketOrder activeOrder = orderMapper.selectOne(new LambdaQueryWrapper<TicketOrder>()
                .eq(TicketOrder::getUserId, message.userId())
                .eq(TicketOrder::getSkuId, message.skuId())
                .in(TicketOrder::getStatus, TicketOrder.STATUS_PENDING, TicketOrder.STATUS_PAID)
                .last("LIMIT 1"));
        if (activeOrder != null) {
            if (message.reservationId().equals(activeOrder.getReservationId())) {
                markMessageSuccess(inbox, activeOrder.getId());
                if (!reservationLedger.tryMarkOrderCreated(
                        message.reservationId(), activeOrder.getId())) {
                    throw new IllegalStateException(
                            "Reservation cannot be claimed by existing order: " + message.reservationId());
                }
                return OrderCreationResult.alreadyCreated(activeOrder.getId());
            }
            return reject(inbox, message, "用户已有该票档的活动订单", true);
        }

        TicketOrder order = TicketOrder.builder()
                .orderNo(UUID.randomUUID().toString().replace("-", ""))
                .reservationId(message.reservationId())
                .userId(message.userId())
                .eventId(message.eventId())
                .skuId(message.skuId())
                .quantity(message.quantity())
                .totalAmount(Math.multiplyExact(message.unitPrice(), message.quantity().longValue()))
                .status(TicketOrder.STATUS_PENDING)
                .build();
        orderMapper.insert(order);

        // 订单可以先 insert，但只有这个 CAS 成功才能提交。失败会抛错，同一事务中的
        // order/inbox insert 都回滚，因而 DLT Release Intent 与创单不会同时成功。
        if (!reservationLedger.tryMarkOrderCreated(reservation.getReservationId(), order.getId())) {
            throw new IllegalStateException(
                    "Reservation lost order-creation race: " + reservation.getReservationId());
        }
        markMessageSuccess(inbox, order.getId());
        log.info("Order created: orderId={} reservationId={} userId={} skuId={}",
                order.getId(), message.reservationId(), message.userId(), message.skuId());
        return OrderCreationResult.created(order.getId());
    }

    @Override
    public OrderDTO getById(Long orderId, Long userId) {
        TicketOrder order = orderMapper.selectById(orderId);
        if (order == null || !order.getUserId().equals(userId)) {
            throw new OrderNotFoundException(orderId);
        }
        return toDTO(order);
    }

    @Override
    public OrderDTO getByOrderNo(String orderNo, Long userId) {
        TicketOrder order = orderMapper.selectOne(
                new LambdaQueryWrapper<TicketOrder>().eq(TicketOrder::getOrderNo, orderNo));
        if (order == null || !order.getUserId().equals(userId)) {
            throw new OrderNotFoundException(orderNo);
        }
        return toDTO(order);
    }

    @Override
    public List<OrderDTO> listByUser(Long userId) {
        return orderMapper.selectList(new LambdaQueryWrapper<TicketOrder>()
                        .eq(TicketOrder::getUserId, userId)
                        .orderByDesc(TicketOrder::getCreateTime))
                .stream().map(this::toDTO).toList();
    }

    private TicketOrderMsg findMessage(String messageId) {
        return messageMapper.selectOne(new LambdaQueryWrapper<TicketOrderMsg>()
                .eq(TicketOrderMsg::getMessageId, messageId));
    }

    private void assertInboxMatches(TicketOrderMsg inbox, TicketRushMessage message) {
        if (!Objects.equals(inbox.getMessageId(), message.messageId())
                || !Objects.equals(inbox.getReservationId(), message.reservationId())
                || !Objects.equals(inbox.getUserId(), message.userId())
                || !Objects.equals(inbox.getEventId(), message.eventId())
                || !Objects.equals(inbox.getSkuId(), message.skuId())
                || !Objects.equals(inbox.getQuantity(), message.quantity())) {
            throw new IllegalArgumentException(
                    "Kafka message does not match existing inbox identity: " + message.messageId());
        }
    }

    private String validateOrderableSku(TicketRushMessage message) {
        if (message.quantity() == null || message.quantity() != 1) {
            return "仅支持每次预占一张票";
        }
        TicketSku sku = skuMapper.selectById(message.skuId());
        if (sku == null) {
            return "票档不存在";
        }
        if (!sku.getEventId().equals(message.eventId())) {
            return "票档与活动不匹配";
        }
        if (!sku.getPrice().equals(message.unitPrice())) {
            return "票价已变化，请重新提交";
        }
        return null;
    }

    private OrderCreationResult reject(TicketOrderMsg inbox, TicketRushMessage message,
                                       String reason, boolean releaseReservation) {
        messageMapper.updateById(TicketOrderMsg.builder()
                .id(inbox.getId())
                .status(TicketOrderMsg.STATUS_FAILED)
                .errorMessage(truncate(reason))
                .build());
        if (releaseReservation) {
            if (!releaseService.scheduleBeforeOrder(
                    message.reservationId(), message.userId(), message.skuId(), reason)) {
                throw new IllegalStateException(
                        "Reservation won a concurrent terminal transition: " + message.reservationId());
            }
        }
        log.warn("Order intake rejected: reservationId={} reason={}", message.reservationId(), reason);
        return OrderCreationResult.rejected(reason);
    }

    private void markMessageSuccess(TicketOrderMsg inbox, Long orderId) {
        messageMapper.updateById(TicketOrderMsg.builder()
                .id(inbox.getId())
                .status(TicketOrderMsg.STATUS_SUCCESS)
                .orderId(orderId)
                .errorMessage(null)
                .build());
    }

    private String truncate(String value) {
        return value == null || value.length() <= 1024 ? value : value.substring(0, 1024);
    }

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
                order.getCancelTime());
    }
}
