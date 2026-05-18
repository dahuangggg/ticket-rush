package dev.dahuangggg.ticketrush.ai.tools;

import dev.dahuangggg.ticketrush.dto.event.EventDTO;
import dev.dahuangggg.ticketrush.dto.event.EventDetailDTO;
import dev.dahuangggg.ticketrush.dto.event.EventListRequest;
import dev.dahuangggg.ticketrush.dto.sku.TicketSkuDTO;
import dev.dahuangggg.ticketrush.service.EventService;
import dev.dahuangggg.ticketrush.service.TicketSkuService;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;

@Component
public class EventQueryTools {

    private final EventService eventService;
    private final TicketSkuService skuService;

    public EventQueryTools(EventService eventService, TicketSkuService skuService) {
        this.eventService = eventService;
        this.skuService = skuService;
    }

    @Tool("根据关键词、城市、日期范围搜索演出活动。返回演出列表：id、标题、艺人、城市、场馆、演出时间。若用户没给具体条件，可不填参数获取全部。")
    public List<EventDTO> searchEvents(
            @P("关键词，如艺人名或演出名，可选") String keyword,
            @P("城市名，可选") String city,
            @P("起始日期 yyyy-MM-dd，可选") String fromDate,
            @P("结束日期 yyyy-MM-dd，可选") String toDate) {
        LocalDate from = parse(fromDate);
        LocalDate to   = parse(toDate);
        // 底层 service 只支持单日精确匹配；为兼顾范围过滤，我们以 from 作为单日参数拉取，再用 to 进一步筛
        LocalDate underlying = from != null ? from : to;
        var req = new EventListRequest(
                isBlank(city) ? null : city,
                isBlank(keyword) ? null : keyword,
                underlying);
        List<EventDTO> all = eventService.listEvents(req);
        if (from != null && to != null) {
            return all.stream()
                    .filter(e -> {
                        var t = e.eventTime();
                        if (t == null) return false;
                        LocalDate d = t.toLocalDate();
                        return !d.isBefore(from) && !d.isAfter(to);
                    })
                    .toList();
        }
        return all;
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
}
