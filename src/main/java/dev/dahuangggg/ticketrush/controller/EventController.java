package dev.dahuangggg.ticketrush.controller;

import dev.dahuangggg.ticketrush.dto.event.EventDetailDTO;
import dev.dahuangggg.ticketrush.dto.event.EventDTO;
import dev.dahuangggg.ticketrush.dto.event.EventListRequest;
import dev.dahuangggg.ticketrush.service.EventService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/events")
public class EventController {

    private final EventService eventService;

    public EventController(EventService eventService) {
        this.eventService = eventService;
    }

    /**
     * 查询活动列表。
     *
     * 支持三个可选过滤条件：
     * - city：城市过滤，精确匹配。
     * - keyword：标题模糊搜索，不参与缓存（避免缓存 key 爆炸）。
     * - date：演出日期过滤，格式 yyyy-MM-dd。
     *
     * 热点活动（is_hot=1）在结果中置顶。
     */
    @GetMapping
    public List<EventDTO> listEvents(EventListRequest request) {
        return eventService.listEvents(request);
    }

    /**
     * 查询活动详情。
     *
     * 经过 Caffeine、布隆过滤器、空值缓存、热点/普通缓存策略的完整处理。
     * 活动不存在时返回 404 EVENT_NOT_FOUND。
     */
    @GetMapping("/{eventId}")
    public EventDetailDTO getEventDetail(@PathVariable Long eventId) {
        return eventService.getEventDetail(eventId);
    }
}
