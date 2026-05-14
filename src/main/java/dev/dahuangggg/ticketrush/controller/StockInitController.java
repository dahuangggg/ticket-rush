package dev.dahuangggg.ticketrush.controller;

import dev.dahuangggg.ticketrush.dto.sku.StockInitResultDTO;
import dev.dahuangggg.ticketrush.service.StockInitService;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/skus")
public class StockInitController {

    private final StockInitService stockInitService;

    public StockInitController(StockInitService stockInitService) {
        this.stockInitService = stockInitService;
    }

    /**
     * 初始化票档的 Redis 实时库存计数器。
     * 幂等接口：已初始化时不覆盖，返回 initialized=false；新建时返回 initialized=true。
     */
    @PostMapping("/{skuId}/init-stock")
    public StockInitResultDTO initStock(@PathVariable Long skuId) {
        boolean initialized = stockInitService.initStock(skuId);
        return new StockInitResultDTO(initialized);
    }
}
