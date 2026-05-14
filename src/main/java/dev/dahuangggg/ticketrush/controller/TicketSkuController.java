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
     * 查询某个活动下的所有票档，按价格升序排序。
     * 如果该活动没有票档，返回空数组，不返回 404。
     */
    @GetMapping("/api/events/{eventId}/skus")
    public List<TicketSkuDTO> listByEvent(@PathVariable Long eventId) {
        return ticketSkuService.listByEvent(eventId);
    }

    /**
     * 根据票档 ID 查询单个票档详情。
     * 如果 ID 不存在或已软删除，返回 404 TICKET_SKU_NOT_FOUND。
     */
    @GetMapping("/api/ticket-skus/{skuId}")
    public TicketSkuDTO getSkuDetail(@PathVariable Long skuId) {
        return ticketSkuService.getSkuDetail(skuId);
    }
}
