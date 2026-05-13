package dev.dahuangggg.ticketrush.service;

import dev.dahuangggg.ticketrush.dto.event.EventDetailDTO;
import dev.dahuangggg.ticketrush.dto.event.EventDTO;
import dev.dahuangggg.ticketrush.dto.event.EventListRequest;

import java.util.List;

public interface EventService {

    /**
     * 查询活动列表，支持按城市、关键词、日期过滤。
     * city + date 组合结果会被缓存；keyword 每次透传到 DB。
     */
    List<EventDTO> listEvents(EventListRequest request);

    /**
     * 查询活动详情，包含完整的缓存策略（布隆过滤器、热点/普通路由、穿透防护）。
     */
    EventDetailDTO getEventDetail(Long eventId);
}
