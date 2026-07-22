package dev.dahuangggg.ticketrush.ai.tools;

import dev.dahuangggg.ticketrush.dto.event.EventDTO;
import dev.dahuangggg.ticketrush.ai.dto.EventSearchResult;
import dev.dahuangggg.ticketrush.dto.event.EventDetailDTO;
import dev.dahuangggg.ticketrush.dto.event.EventListRequest;
import dev.dahuangggg.ticketrush.dto.sku.TicketSkuDTO;
import dev.dahuangggg.ticketrush.service.EventService;
import dev.dahuangggg.ticketrush.service.TicketSkuService;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;

@Component
public class EventQueryTools {

    private final EventService eventService;
    private final TicketSkuService skuService;

    public EventQueryTools(EventService eventService, TicketSkuService skuService) {
        this.eventService = eventService;
        this.skuService = skuService;
    }

    @Tool("根据演出标题关键词、城市、日期范围搜索演出。返回 ok/error/events；参数错误与没有匹配结果会明确区分。")
    public EventSearchResult searchEvents(
            @P("演出标题关键词，可选") String keyword,
            @P("城市名，可选") String city,
            @P("起始日期 yyyy-MM-dd，可选") String fromDate,
            @P("结束日期 yyyy-MM-dd，可选") String toDate) {
        LocalDate from;
        LocalDate to;
        try {
            from = parse(fromDate);
            to = parse(toDate);
        } catch (DateTimeParseException invalidDate) {
            return EventSearchResult.invalid("日期格式必须是 yyyy-MM-dd");
        }
        if (from != null && to != null && from.isAfter(to)) {
            return EventSearchResult.invalid("起始日期不能晚于结束日期");
        }

        // EventService 的 date 是“单日精确匹配”。只有同一天范围才能直接下推；
        // 其他范围先按城市/标题查询，再在工具边界做闭区间过滤，不能只查 from 那一天。
        LocalDate underlying = from != null && from.equals(to) ? from : null;
        var req = new EventListRequest(
                isBlank(city) ? null : city,
                isBlank(keyword) ? null : keyword,
                underlying);
        List<EventDTO> all = eventService.listEvents(req);
        List<EventDTO> filtered = all.stream()
                .filter(event -> inRange(event, from, to))
                .toList();
        return EventSearchResult.success(filtered);
    }

    @Tool("查询单个演出的详细信息（含描述）")
    public EventDetailDTO getEventDetail(@P("演出ID") Long eventId) {
        return eventService.getEventDetail(eventId);
    }

    @Tool("查询演出的票档(SKU)列表：每档的名称、价格(分)、库存、开抢时间、结束时间、状态")
    public List<TicketSkuDTO> listSkus(@P("演出ID") Long eventId) {
        return skuService.listByEvent(eventId);
    }

    private static boolean isBlank(String s) { return s == null || s.isBlank(); }
    private static LocalDate parse(String s) {
        if (isBlank(s)) return null;
        return LocalDate.parse(s.trim());
    }

    private static boolean inRange(EventDTO event, LocalDate from, LocalDate to) {
        if (from == null && to == null) return true;
        if (event.eventTime() == null) return false;
        LocalDate date = event.eventTime().toLocalDate();
        return (from == null || !date.isBefore(from)) && (to == null || !date.isAfter(to));
    }
}
