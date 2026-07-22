package dev.dahuangggg.ticketrush.service;

import dev.dahuangggg.ticketrush.dto.sku.InventoryReconciliationDTO;

/** Inventory Ledger/Reconciliation Module。 */
public interface InventoryReconciliationService {

    InventoryReconciliationDTO inspect(Long skuId);
}
