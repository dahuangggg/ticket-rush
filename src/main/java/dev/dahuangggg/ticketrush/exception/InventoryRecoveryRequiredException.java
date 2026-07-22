package dev.dahuangggg.ticketrush.exception;

/** Redis 销售证据不完整时拒绝自动重开库存。 */
public class InventoryRecoveryRequiredException extends RuntimeException {

    public InventoryRecoveryRequiredException(Long skuId) {
        super("SKU " + skuId + " requires a paused, audited inventory recovery");
    }
}
