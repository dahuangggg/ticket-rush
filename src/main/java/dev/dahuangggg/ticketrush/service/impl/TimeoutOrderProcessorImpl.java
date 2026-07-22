package dev.dahuangggg.ticketrush.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import dev.dahuangggg.ticketrush.entity.TicketOrder;
import dev.dahuangggg.ticketrush.mapper.TicketOrderMapper;
import dev.dahuangggg.ticketrush.service.InventoryReleaseService;
import dev.dahuangggg.ticketrush.service.TimeoutOrderProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;

/** 在独立事务内完成单笔订单超时 CAS 与 Release Intent 持久化。 */
@Service
public class TimeoutOrderProcessorImpl implements TimeoutOrderProcessor {

    private static final Logger log = LoggerFactory.getLogger(TimeoutOrderProcessorImpl.class);

    private final TicketOrderMapper orderMapper;
    private final InventoryReleaseService releaseService;
    private final Clock clock;

    public TimeoutOrderProcessorImpl(TicketOrderMapper orderMapper,
                                     InventoryReleaseService releaseService,
                                     Clock clock) {
        this.orderMapper = orderMapper;
        this.releaseService = releaseService;
        this.clock = clock;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean timeout(Long orderId) {
        TicketOrder order = orderMapper.selectById(orderId);
        if (order == null) {
            return false;
        }

        int updated = orderMapper.update(null, new LambdaUpdateWrapper<TicketOrder>()
                .eq(TicketOrder::getId, orderId)
                .eq(TicketOrder::getStatus, TicketOrder.STATUS_PENDING)
                .set(TicketOrder::getStatus, TicketOrder.STATUS_TIMEOUT)
                .set(TicketOrder::getCancelTime, LocalDateTime.now(clock)));
        if (updated == 0) {
            return false;
        }

        // scheduleAfterOrder 以 REQUIRED 加入当前事务，确保状态与 Intent 同成同败。
        releaseService.scheduleAfterOrder(order.getReservationId(), order.getId(), order.getUserId(),
                order.getSkuId(), "PAYMENT_TIMEOUT");
        log.info("Order timed out and release intent committed: orderId={} reservationId={}",
                order.getId(), order.getReservationId());
        return true;
    }
}
