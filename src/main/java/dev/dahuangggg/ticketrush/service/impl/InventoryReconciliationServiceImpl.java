package dev.dahuangggg.ticketrush.service.impl;

import dev.dahuangggg.ticketrush.dto.sku.InventoryReconciliationDTO;
import dev.dahuangggg.ticketrush.entity.TicketSku;
import dev.dahuangggg.ticketrush.exception.TicketSkuNotFoundException;
import dev.dahuangggg.ticketrush.mapper.RushReservationMapper;
import dev.dahuangggg.ticketrush.mapper.TicketOrderMapper;
import dev.dahuangggg.ticketrush.mapper.TicketSkuMapper;
import dev.dahuangggg.ticketrush.service.InventoryReconciliationService;
import dev.dahuangggg.ticketrush.service.RushReservationStore;
import org.springframework.stereotype.Service;

@Service
public class InventoryReconciliationServiceImpl implements InventoryReconciliationService {

    private final TicketSkuMapper ticketSkuMapper;
    private final TicketOrderMapper ticketOrderMapper;
    private final RushReservationMapper reservationMapper;
    private final RushReservationStore reservationStore;

    public InventoryReconciliationServiceImpl(TicketSkuMapper ticketSkuMapper,
                                              TicketOrderMapper ticketOrderMapper,
                                              RushReservationMapper reservationMapper,
                                              RushReservationStore reservationStore) {
        this.ticketSkuMapper = ticketSkuMapper;
        this.ticketOrderMapper = ticketOrderMapper;
        this.reservationMapper = reservationMapper;
        this.reservationStore = reservationStore;
    }

    @Override
    public InventoryReconciliationDTO inspect(Long skuId) {
        TicketSku sku = ticketSkuMapper.selectById(skuId);
        if (sku == null) {
            throw new TicketSkuNotFoundException(skuId);
        }
        long held = reservationMapper.sumHeldQuantity(skuId);
        long activeOrders = ticketOrderMapper.sumActiveQuantity(skuId);
        long durableConsumed = Math.addExact(held, activeOrders);
        // buyers set 在 Lua 线性化点就写入，因此能覆盖“已预占、尚未 relay 到 MySQL”的窗口。
        long redisConsumed = reservationStore.getConsumedReservationCount(skuId);
        long recoveryConsumed = Math.max(durableConsumed, redisConsumed);
        int expectedAvailable = Math.max(0,
                Math.toIntExact((long) sku.getStock() - recoveryConsumed));
        Integer available = reservationStore.getAvailableStock(skuId);
        boolean inventoryConserved = available != null
                && (long) sku.getStock() == available.longValue() + recoveryConsumed;
        // 数量相等只表示当前已追平；告警层仍应结合 Reservation 年龄设置容忍窗口。
        boolean ledgerCaughtUp = redisConsumed == durableConsumed;
        boolean balanced = inventoryConserved && ledgerCaughtUp;
        return new InventoryReconciliationDTO(
                skuId,
                sku.getStock(),
                available,
                held,
                activeOrders,
                redisConsumed,
                expectedAvailable,
                inventoryConserved,
                ledgerCaughtUp,
                balanced
        );
    }
}
