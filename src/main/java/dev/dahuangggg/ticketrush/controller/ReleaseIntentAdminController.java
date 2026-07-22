package dev.dahuangggg.ticketrush.controller;

import dev.dahuangggg.ticketrush.dto.sku.ReleaseIntentRetryDTO;
import dev.dahuangggg.ticketrush.service.InventoryReleaseService;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 库存补偿的显式管理面；路由受 AdminAuthInterceptor 保护。 */
@RestController
@RequestMapping("/api/admin/release-intents")
public class ReleaseIntentAdminController {

    private final InventoryReleaseService releaseService;

    public ReleaseIntentAdminController(InventoryReleaseService releaseService) {
        this.releaseService = releaseService;
    }

    @PostMapping("/{reservationId}/retry")
    public ReleaseIntentRetryDTO retry(@PathVariable String reservationId) {
        return new ReleaseIntentRetryDTO(releaseService.requeueFailed(reservationId));
    }
}
