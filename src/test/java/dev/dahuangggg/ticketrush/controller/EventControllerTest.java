package dev.dahuangggg.ticketrush.controller;

import dev.dahuangggg.ticketrush.dto.event.EventDetailDTO;
import dev.dahuangggg.ticketrush.dto.event.EventDTO;
import dev.dahuangggg.ticketrush.dto.event.EventListRequest;
import dev.dahuangggg.ticketrush.exception.EventNotFoundException;
import dev.dahuangggg.ticketrush.service.EventService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class EventControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void listEventsReturnsOk() throws Exception {
        mockMvc.perform(get("/api/events").param("city", "上海"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(1001))
                .andExpect(jsonPath("$[0].title").value("周杰伦2026世界巡回演唱会"));
    }

    @Test
    void getEventDetailReturnsOk() throws Exception {
        mockMvc.perform(get("/api/events/1001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1001))
                .andExpect(jsonPath("$.description").value("演出详情"));
    }

    @Test
    void getEventDetailReturns404WhenNotFound() throws Exception {
        mockMvc.perform(get("/api/events/9999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("EVENT_NOT_FOUND"));
    }

    @TestConfiguration
    static class EventControllerTestConfig {

        @Bean
        @Primary
        FakeEventService fakeEventService() {
            return new FakeEventService();
        }
    }

    static class FakeEventService implements EventService {

        @Override
        public List<EventDTO> listEvents(EventListRequest request) {
            return List.of(new EventDTO(1001L, "周杰伦2026世界巡回演唱会", "周杰伦",
                    "上海", "梅赛德斯奔驰文化中心",
                    LocalDateTime.of(2026, 8, 1, 20, 0),
                    "https://example.com/cover.jpg", 1, 1));
        }

        @Override
        public EventDetailDTO getEventDetail(Long eventId) {
            if (eventId.equals(9999L)) {
                throw new EventNotFoundException(eventId);
            }
            return new EventDetailDTO(1001L, "周杰伦2026世界巡回演唱会", "周杰伦",
                    "上海", "梅赛德斯奔驰文化中心",
                    LocalDateTime.of(2026, 8, 1, 20, 0),
                    "https://example.com/cover.jpg", "演出详情", 1, 1);
        }

        @Override
        public void invalidateCache(Long eventId) {
            // no-op in test
        }
    }
}
