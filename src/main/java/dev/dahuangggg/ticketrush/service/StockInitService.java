package dev.dahuangggg.ticketrush.service;

public interface StockInitService {

    /**
     * 初始化指定票档的 Redis 实时库存计数器（SET NX）。
     * 首次开放使用配置库存；曾开放过的 SKU 仅在 buyers 证据仍存在时按 Ledger 推导值恢复。
     * 全量 Redis 证据丢失时失败关闭，要求人工暂停与审计。
     * 若票档不存在或已软删除，抛出 TicketSkuNotFoundException。
     *
     * @return true 表示新建计数器；false 表示计数器已存在（未覆盖）
     */
    boolean initStock(Long skuId);

    /**
     * 从 Redis 读取指定票档的实时库存。
     *
     * @return 当前库存值；若计数器尚未初始化则返回 null
     */
    Integer getAvailableStock(Long skuId);
}
