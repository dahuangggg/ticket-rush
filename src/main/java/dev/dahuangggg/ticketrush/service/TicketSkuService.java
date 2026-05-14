package dev.dahuangggg.ticketrush.service;

import dev.dahuangggg.ticketrush.dto.sku.TicketSkuDTO;

import java.util.List;

public interface TicketSkuService {

    /**
     * Returns all non-deleted SKUs for the given event, ordered by price ascending.
     * Does not validate whether the event itself exists.
     */
    List<TicketSkuDTO> listByEvent(Long eventId);

    /**
     * Returns a single SKU by id.
     * Throws TicketSkuNotFoundException if the id does not exist or is soft-deleted.
     */
    TicketSkuDTO getSkuDetail(Long skuId);
}
