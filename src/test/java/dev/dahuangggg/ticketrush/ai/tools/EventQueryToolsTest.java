package dev.dahuangggg.ticketrush.ai.tools;

import dev.dahuangggg.ticketrush.dto.event.EventDTO;
import dev.dahuangggg.ticketrush.dto.event.EventListRequest;
import dev.dahuangggg.ticketrush.service.EventService;
import dev.dahuangggg.ticketrush.service.TicketSkuService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class EventQueryToolsTest {

    @Test
    void multiDayRangeQueriesAllCandidatesThenFiltersClosedInterval() {
        EventService events = mock(EventService.class);
        TicketSkuService skus = mock(TicketSkuService.class);
        when(events.listEvents(any())).thenReturn(List.of(
                event(1L, "2026-07-20T20:00:00"),
                event(2L, "2026-07-21T20:00:00"),
                event(3L, "2026-07-22T20:00:00")));

        var result = new EventQueryTools(events, skus)
                .searchEvents("巡演", "上海", "2026-07-20", "2026-07-21");

        assertThat(result.ok()).isTrue();
        assertThat(result.events()).extracting(EventDTO::id).containsExactly(1L, 2L);
        ArgumentCaptor<EventListRequest> request = ArgumentCaptor.forClass(EventListRequest.class);
        verify(events).listEvents(request.capture());
        assertThat(request.getValue().date()).isNull();
    }

    @Test
    void invalidRangeReturnsExplicitToolErrorWithoutQueryingService() {
        EventService events = mock(EventService.class);

        var result = new EventQueryTools(events, mock(TicketSkuService.class))
                .searchEvents(null, null, "2026-07-22", "2026-07-20");

        assertThat(result.ok()).isFalse();
        assertThat(result.error()).contains("起始日期");
        verifyNoInteractions(events);
    }

    @Test
    void malformedDateIsNotConfusedWithEmptySearchResult() {
        EventService events = mock(EventService.class);

        var result = new EventQueryTools(events, mock(TicketSkuService.class))
                .searchEvents(null, null, "July 20", null);

        assertThat(result.ok()).isFalse();
        assertThat(result.error()).contains("yyyy-MM-dd");
        verifyNoInteractions(events);
    }

    private static EventDTO event(Long id, String time) {
        return new EventDTO(id, "巡演" + id, "艺人", "上海", "场馆",
                LocalDateTime.parse(time), null, 1, 0);
    }
}
