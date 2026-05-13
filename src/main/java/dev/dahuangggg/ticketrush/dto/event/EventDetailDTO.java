package dev.dahuangggg.ticketrush.dto.event;

import java.time.LocalDateTime;

/**
 * 活动详情 DTO，包含完整字段。
 */
public record EventDetailDTO(
        Long id,
        String title,
        String artist,
        String city,
        String venue,
        LocalDateTime eventTime,
        String coverUrl,
        String description,
        Integer status,
        Integer isHot
) {
}
