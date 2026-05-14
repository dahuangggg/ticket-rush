package dev.dahuangggg.ticketrush.service;

public interface StockInitService {

    /**
     * Initializes the Redis stock counter for the given SKU using SET NX.
     * Reads the stock value from MySQL and writes it to Redis only if the key does not exist.
     * Throws TicketSkuNotFoundException if the SKU id does not exist or is soft-deleted.
     *
     * @return true if the key was newly created; false if it already existed (not overwritten)
     */
    boolean initStock(Long skuId);

    /**
     * Returns the current real-time stock from Redis.
     *
     * @return the stock value, or null if the counter has not been initialized yet
     */
    Integer getAvailableStock(Long skuId);
}
