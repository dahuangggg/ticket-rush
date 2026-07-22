package dev.dahuangggg.ticketrush.dto.sku;

/**
 * 库存守恒检查。redisConsumedReservations 包含尚未 relay 到 MySQL 的短暂预占；
 * durableConsumed = heldReservations + activeOrderQuantity。
 */
public record InventoryReconciliationDTO(
        Long skuId,
        int initialStock,
        Integer availableStock,
        long heldReservations,
        long activeOrderQuantity,
        long redisConsumedReservations,
        int expectedAvailableStock,
        boolean inventoryConserved,
        boolean ledgerCaughtUp,
        boolean balanced
) {
}
