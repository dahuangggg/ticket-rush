package dev.dahuangggg.ticketrush.controller;

import dev.dahuangggg.ticketrush.dto.sku.TicketSkuDTO;
import dev.dahuangggg.ticketrush.service.TicketSkuService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class TicketSkuController {

    private final TicketSkuService ticketSkuService;

    public TicketSkuController(TicketSkuService ticketSkuService) {
        this.ticketSkuService = ticketSkuService;
    }

    /**
     * Lists all SKUs for an event, ordered by price ascending.
     * Returns an empty array if the event has no SKUs (not 404).
     */
    @GetMapping("/api/events/{eventId}/skus")
    public List<TicketSkuDTO> listByEvent(@PathVariable Long eventId) {
        return ticketSkuService.listByEvent(eventId);
    }

    /**
     * Returns a single SKU by its id.
     * Returns 404 TICKET_SKU_NOT_FOUND if the id does not exist or is soft-deleted.
     */
    @GetMapping("/api/ticket-skus/{skuId}")
    public TicketSkuDTO getSkuDetail(@PathVariable Long skuId) {
        return ticketSkuService.getSkuDetail(skuId);
    }
}
