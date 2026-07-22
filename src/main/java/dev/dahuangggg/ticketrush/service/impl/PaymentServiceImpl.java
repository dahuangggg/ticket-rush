package dev.dahuangggg.ticketrush.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import dev.dahuangggg.ticketrush.entity.TicketOrder;
import dev.dahuangggg.ticketrush.exception.OrderNotFoundException;
import dev.dahuangggg.ticketrush.exception.OrderNotPendingException;
import dev.dahuangggg.ticketrush.mapper.TicketOrderMapper;
import dev.dahuangggg.ticketrush.service.InventoryReleaseService;
import dev.dahuangggg.ticketrush.service.PaymentService;
import dev.dahuangggg.ticketrush.service.RushReservationLedger;
import dev.dahuangggg.ticketrush.service.RushReservationStore;
import dev.dahuangggg.ticketrush.service.TimeoutOrderProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Order Lifecycle Module。
 *
 * <p>支付、取消和超时使用数据库 CAS。取消/超时不会在事务内调用 Redis，而是与订单状态
 * 一起提交 Release Intent，消除“Redis 成功但数据库回滚”的双写窗口。</p>
 */
@Service
public class PaymentServiceImpl implements PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentServiceImpl.class);
    private static final int TIMEOUT_MINUTES = 15;
    private static final int TIMEOUT_BATCH_SIZE = 100;

    private final TicketOrderMapper orderMapper;
    private final InventoryReleaseService releaseService;
    private final RushReservationLedger reservationLedger;
    private final RushReservationStore reservationStore;
    private final TimeoutOrderProcessor timeoutOrderProcessor;
    private final Clock clock;

    public PaymentServiceImpl(TicketOrderMapper orderMapper,
                              InventoryReleaseService releaseService,
                              RushReservationLedger reservationLedger,
                              RushReservationStore reservationStore,
                              TimeoutOrderProcessor timeoutOrderProcessor,
                              Clock clock) {
        this.orderMapper = orderMapper;
        this.releaseService = releaseService;
        this.reservationLedger = reservationLedger;
        this.reservationStore = reservationStore;
        this.timeoutOrderProcessor = timeoutOrderProcessor;
        this.clock = clock;
    }

    @Override
    @Transactional
    public void pay(Long orderId, Long userId) {
        TicketOrder order = requireOwnedOrder(orderId, userId);
        int updated = orderMapper.update(null, new LambdaUpdateWrapper<TicketOrder>()
                .eq(TicketOrder::getId, orderId)
                .eq(TicketOrder::getStatus, TicketOrder.STATUS_PENDING)
                .set(TicketOrder::getStatus, TicketOrder.STATUS_PAID)
                .set(TicketOrder::getPayTime, LocalDateTime.now(clock)));
        if (updated == 0) {
            throw new OrderNotPendingException(orderId);
        }
        if (!reservationLedger.tryMarkPaid(order.getReservationId())) {
            throw new IllegalStateException(
                    "Reservation cannot transition to paid: " + order.getReservationId());
        }
        synchronizePaidStateAfterCommit(order.getReservationId());
        log.info("Order paid: orderId={} reservationId={} userId={}",
                orderId, order.getReservationId(), userId);
    }

    @Override
    @Transactional
    public void cancel(Long orderId, Long userId) {
        TicketOrder order = requireOwnedOrder(orderId, userId);
        int updated = orderMapper.update(null, new LambdaUpdateWrapper<TicketOrder>()
                .eq(TicketOrder::getId, orderId)
                .eq(TicketOrder::getStatus, TicketOrder.STATUS_PENDING)
                .set(TicketOrder::getStatus, TicketOrder.STATUS_CANCELED)
                .set(TicketOrder::getCancelTime, LocalDateTime.now(clock)));
        if (updated == 0) {
            throw new OrderNotPendingException(orderId);
        }
        releaseService.scheduleAfterOrder(order.getReservationId(), order.getId(), order.getUserId(),
                order.getSkuId(), "USER_CANCELED");
        log.info("Order canceled and release intent committed: orderId={} reservationId={}",
                orderId, order.getReservationId());
    }

    @Override
    @Scheduled(fixedDelayString = "${ticket-rush.order-timeout.scan-delay-ms:60000}")
    public void cancelTimeoutOrders() {
        LocalDateTime deadline = LocalDateTime.now(clock).minusMinutes(TIMEOUT_MINUTES);
        LocalDateTime afterCreateTime = null;
        Long afterId = null;

        while (true) {
            List<TicketOrder> candidates = orderMapper.selectTimeoutCandidates(
                    TicketOrder.STATUS_PENDING,
                    deadline,
                    afterCreateTime,
                    afterId,
                    TIMEOUT_BATCH_SIZE);
            if (candidates.isEmpty()) {
                return;
            }

            for (TicketOrder candidate : candidates) {
                try {
                    timeoutOrderProcessor.timeout(candidate.getId());
                } catch (RuntimeException e) {
                    // 每单使用独立事务。keyset 游标仍会越过坏行，整页 poison 也不会饿死后续订单。
                    log.warn("Failed to time out order; later candidates will continue: orderId={}",
                            candidate.getId(), e);
                }
            }
            TicketOrder last = candidates.get(candidates.size() - 1);
            afterCreateTime = last.getCreateTime();
            afterId = last.getId();
            if (candidates.size() < TIMEOUT_BATCH_SIZE) {
                return;
            }
        }
    }

    private TicketOrder requireOwnedOrder(Long orderId, Long userId) {
        TicketOrder order = orderMapper.selectById(orderId);
        if (order == null || !order.getUserId().equals(userId)) {
            throw new OrderNotFoundException(orderId);
        }
        return order;
    }

    private void synchronizePaidStateAfterCommit(String reservationId) {
        Runnable synchronize = () -> {
            try {
                reservationStore.markPaid(reservationId);
            } catch (RuntimeException e) {
                // MySQL ledger 是权威状态；worker 在释放前也会校验 ledger。
                // Redis 只是查询加速和额外防线，同步失败不能回滚已成功的支付。
                log.warn("Paid order committed but Redis reservation state sync failed: reservationId={}",
                        reservationId, e);
            }
        };
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            synchronize.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                synchronize.run();
            }
        });
    }
}
