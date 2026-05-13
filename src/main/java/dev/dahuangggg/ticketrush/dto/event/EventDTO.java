package dev.dahuangggg.ticketrush.dto.event;

import java.time.LocalDateTime;

/**
 * 活动列表项 DTO。
 * 不包含 description，避免列表页传输过多数据。
 * isHot 供前端展示"热门"标签和置顶排序。
 */
public record EventDTO(
        Long id,
        String title,
        String artist,
        String city,
        String venue,
        LocalDateTime eventTime,
        String coverUrl,
        Integer status,
        Integer isHot
) {
}
