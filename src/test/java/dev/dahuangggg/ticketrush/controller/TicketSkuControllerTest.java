package dev.dahuangggg.ticketrush.controller;

import dev.dahuangggg.ticketrush.dto.sku.TicketSkuDTO;
import dev.dahuangggg.ticketrush.exception.TicketSkuNotFoundException;
import dev.dahuangggg.ticketrush.service.TicketSkuService;
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
class TicketSkuControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void listByEventReturnsSkus() throws Exception {
        mockMvc.perform(get("/api/events/2001/skus"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(3001))
                .andExpect(jsonPath("$[0].name").value("看台票 380"))
                .andExpect(jsonPath("$[1].id").value(3002))
                .andExpect(jsonPath("$[1].name").value("看台票 580"));
    }

    @Test
    void listByEventReturnsEmptyWhenNoSkus() throws Exception {
        mockMvc.perform(get("/api/events/9999/skus"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    void getSkuDetailReturnsOk() throws Exception {
        mockMvc.perform(get("/api/ticket-skus/3001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(3001))
                .andExpect(jsonPath("$.price").value(38000))
                .andExpect(jsonPath("$.limitPerUser").value(1));
    }

    @Test
    void getSkuDetailReturns404WhenNotFound() throws Exception {
        mockMvc.perform(get("/api/ticket-skus/9999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TICKET_SKU_NOT_FOUND"));
    }

    @TestConfiguration
    static class TicketSkuControllerTestConfig {

        @Bean
        @Primary
        FakeTicketSkuService fakeTicketSkuService() {
            return new FakeTicketSkuService();
        }
    }

    static class FakeTicketSkuService implements TicketSkuService {

        private static final LocalDateTime SALE_START = LocalDateTime.of(2026, 5, 20, 12, 0);
        private static final LocalDateTime SALE_END   = LocalDateTime.of(2026, 7, 18, 18, 0);

        @Override
        public List<TicketSkuDTO> listByEvent(Long eventId) {
            if (!eventId.equals(2001L)) {
                return List.of();
            }
            return List.of(
                    new TicketSkuDTO(3001L, 2001L, "看台票 380", 38000L, 180, SALE_START, SALE_END, 1, 1, true),
                    new TicketSkuDTO(3002L, 2001L, "看台票 580", 58000L, 120, SALE_START, SALE_END, 1, 1, true)
            );
        }

        @Override
        public TicketSkuDTO getSkuDetail(Long skuId) {
            if (!skuId.equals(3001L)) {
                throw new TicketSkuNotFoundException(skuId);
            }
            return new TicketSkuDTO(3001L, 2001L, "看台票 380", 38000L, 180, SALE_START, SALE_END, 1, 1, true);
        }
    }
}
