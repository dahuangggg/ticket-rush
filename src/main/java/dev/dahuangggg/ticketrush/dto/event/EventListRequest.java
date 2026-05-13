package dev.dahuangggg.ticketrush.dto.event;

import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;

/**
 * 活动列表查询参数。
 * city 和 date 组合会被缓存；keyword 模糊搜索不参与缓存 key，每次透传到数据库。
 */
public record EventListRequest(
        String city,
        String keyword,
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        LocalDate date
) {
}
