package dev.dahuangggg.ticketrush.service.impl;

import dev.dahuangggg.ticketrush.dto.sku.InventoryReconciliationDTO;
import dev.dahuangggg.ticketrush.entity.TicketSku;
import dev.dahuangggg.ticketrush.exception.InventoryRecoveryRequiredException;
import dev.dahuangggg.ticketrush.exception.TicketSkuNotFoundException;
import dev.dahuangggg.ticketrush.mapper.TicketSkuMapper;
import dev.dahuangggg.ticketrush.service.InventoryReconciliationService;
import dev.dahuangggg.ticketrush.service.InventoryReleaseService;
import dev.dahuangggg.ticketrush.service.RushReservationStore;
import dev.dahuangggg.ticketrush.service.SkuInventoryMutex;
import dev.dahuangggg.ticketrush.service.StockInitService;
import org.springframework.stereotype.Service;

/**
 * SKU 抢票元数据与 Redis 可售库存的预热入口。
 * 首次初始化与灾备恢复分开；证据不足时失败关闭，不把活动 SKU 重置为初始库存。
 */
@Service
public class StockInitServiceImpl implements StockInitService {

    private final RushReservationStore reservationStore;
    private final TicketSkuMapper ticketSkuMapper;
    private final InventoryReconciliationService reconciliationService;
    private final InventoryReleaseService releaseService;
    private final SkuInventoryMutex skuInventoryMutex;

    public StockInitServiceImpl(RushReservationStore reservationStore,
                                TicketSkuMapper ticketSkuMapper,
                                InventoryReconciliationService reconciliationService,
                                InventoryReleaseService releaseService,
                                SkuInventoryMutex skuInventoryMutex) {
        this.reservationStore = reservationStore;
        this.ticketSkuMapper = ticketSkuMapper;
        this.reconciliationService = reconciliationService;
        this.releaseService = releaseService;
        this.skuInventoryMutex = skuInventoryMutex;
    }

    @Override
    public boolean initStock(Long skuId) {
        return skuInventoryMutex.execute(skuId, () -> initStockLocked(skuId));
    }

    private boolean initStockLocked(Long skuId) {
        TicketSku sku = ticketSkuMapper.selectById(skuId);
        if (sku == null) {
            throw new TicketSkuNotFoundException(skuId);
        }

        Integer available = reservationStore.getAvailableStock(skuId);
        int openingState = sku.getStockInitialized();
        if (openingState == TicketSku.STOCK_OPENED && available != null) {
            // initializeSku 使用 SET NX，不覆盖实时计数；这里同时刷新销售元数据。
            reservationStore.initializeSku(sku, available);
            return false;
        }

        if (openingState == TicketSku.STOCK_NEW) {
            if (available != null) {
                // DB 还声称从未开放，但 Redis 已有库存，不能猜测它是否来自旧流量或人工写入。
                throw new InventoryRecoveryRequiredException(skuId);
            }
            if (ticketSkuMapper.claimStockOpeningIfNew(skuId) != 1) {
                return initStockLocked(skuId);
            }
            sku.setStockInitialized(TicketSku.STOCK_OPENING);
            return completeFirstOpening(sku);
        }

        if (openingState == TicketSku.STOCK_OPENING) {
            return completeFirstOpening(sku);
        }

        if (openingState != TicketSku.STOCK_OPENED) {
            throw new InventoryRecoveryRequiredException(skuId);
        }
        return recoverMissingStock(sku);
    }

    @Override
    public Integer getAvailableStock(Long skuId) {
        return reservationStore.getAvailableStock(skuId);
    }

    private boolean recoverMissingStock(TicketSku sku) {
        Long skuId = sku.getId();
        Integer recoveredByAnotherCaller = reservationStore.getAvailableStock(skuId);
        if (recoveredByAnotherCaller != null) {
            reservationStore.initializeSku(sku, recoveredByAnotherCaller);
            return false;
        }

        // 曾经开放过库存却只剩缺失的 stock key 时，必须保留 buyers 证据；全量 Redis
        // 丢失无法排除“Lua 已扣减但尚未写入 MySQL”的窗口，因此拒绝自动重开。
        // PENDING/FAILED Release Intent 同样会让 MySQL 与 Redis 暂时处于不同阶段；恢复锁
        // 与 worker 共用，且在锁内检查 intent，避免把已经释放的单位再次算作占用。
        if (!reservationStore.hasBuyerState(skuId)
                || releaseService.hasUnsettledForSku(skuId)) {
            throw new InventoryRecoveryRequiredException(skuId);
        }
        InventoryReconciliationDTO reconciliation = reconciliationService.inspect(skuId);
        return reservationStore.initializeSku(sku, reconciliation.expectedAvailableStock());
    }

    private boolean completeFirstOpening(TicketSku sku) {
        Integer existing = reservationStore.getAvailableStock(sku.getId());
        if (existing != null && !existing.equals(sku.getStock())) {
            throw new InventoryRecoveryRequiredException(sku.getId());
        }

        // Redis 先以不可售状态准备。即使进程在任一步崩溃，OPENING 重试都不会提前放量，
        // SET NX 也不会覆盖已经准备好的 configured capacity。
        int saleStatus = sku.getStatus();
        sku.setStatus(TicketSku.STATUS_NOT_STARTED);
        boolean created;
        try {
            created = reservationStore.initializeSku(sku, sku.getStock());
        } finally {
            sku.setStatus(saleStatus);
        }
        Integer prepared = reservationStore.getAvailableStock(sku.getId());
        if (!sku.getStock().equals(prepared)) {
            throw new InventoryRecoveryRequiredException(sku.getId());
        }
        if (ticketSkuMapper.markStockOpenedIfOpening(sku.getId()) != 1) {
            TicketSku current = ticketSkuMapper.selectById(sku.getId());
            if (current == null || current.getStockInitialized() != TicketSku.STOCK_OPENED) {
                throw new InventoryRecoveryRequiredException(sku.getId());
            }
        }

        // 只有 MySQL 已声明 OPENED 后才把真实售卖状态写入 Redis metadata。
        reservationStore.initializeSku(sku, prepared);
        return created;
    }
}
