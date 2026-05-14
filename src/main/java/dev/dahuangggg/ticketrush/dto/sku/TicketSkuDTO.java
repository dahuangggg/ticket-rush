package dev.dahuangggg.ticketrush.dto.sku;

import java.time.LocalDateTime;

/**
 * 票档 DTO，用于列表响应和详情响应。
 * 暴露 saleStartTime 和 saleEndTime，让前端可以计算在售状态，无需查询 status 字段。
 */
public record TicketSkuDTO(
        Long id,
        Long eventId,
        String name,
        Long price,
        Integer stock,
        LocalDateTime saleStartTime,
        LocalDateTime saleEndTime,
        Integer limitPerUser,
        Integer status
) {
}
