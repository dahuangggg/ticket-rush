package dev.dahuangggg.ticketrush.controller;

import dev.dahuangggg.ticketrush.exception.TicketSkuNotFoundException;
import dev.dahuangggg.ticketrush.service.StockInitService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class StockInitControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void initStock_returnsInitializedTrue_whenKeyWasNew() throws Exception {
        mockMvc.perform(post("/api/admin/skus/3001/init-stock"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.initialized").value(true));
    }

    @Test
    void initStock_returnsInitializedFalse_whenAlreadyInitialized() throws Exception {
        mockMvc.perform(post("/api/admin/skus/3002/init-stock"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.initialized").value(false));
    }

    @Test
    void initStock_returns404_whenSkuNotFound() throws Exception {
        mockMvc.perform(post("/api/admin/skus/9999/init-stock"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TICKET_SKU_NOT_FOUND"));
    }

    @TestConfiguration
    static class StockInitControllerTestConfig {

        @Bean
        @Primary
        FakeStockInitService fakeStockInitService() {
            return new FakeStockInitService();
        }
    }

    static class FakeStockInitService implements StockInitService {

        @Override
        public boolean initStock(Long skuId) {
            if (skuId.equals(9999L)) {
                throw new TicketSkuNotFoundException(skuId);
            }
            return skuId.equals(3001L);
        }

        @Override
        public Integer getAvailableStock(Long skuId) {
            return null;
        }
    }
}
