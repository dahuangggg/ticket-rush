package dev.dahuangggg.ticketrush.controller;

import dev.dahuangggg.ticketrush.dto.order.OrderDTO;
import dev.dahuangggg.ticketrush.domain.order.OrderCreationResult;
import dev.dahuangggg.ticketrush.entity.User;
import dev.dahuangggg.ticketrush.exception.OrderNotFoundException;
import dev.dahuangggg.ticketrush.infrastructure.mq.TicketRushMessage;
import dev.dahuangggg.ticketrush.security.JwtTokenService;
import dev.dahuangggg.ticketrush.service.OrderService;
import org.junit.jupiter.api.BeforeEach;
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

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class OrderControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenService jwtTokenService;

    private String token;

    // 与 FakeOrderService 保持一致：userId=1001 拥有 orderId=4001 / orderNo=TR202605130001
    private static final Long OWNER_USER_ID   = 1001L;
    private static final Long EXISTING_ORDER_ID = 4001L;
    private static final String EXISTING_ORDER_NO = "TR202605130001";

    @BeforeEach
    void setup() {
        User user = User.builder()
                .id(OWNER_USER_ID).phone("13800138000").nickName("测试用户").icon("").build();
        token = jwtTokenService.issueAccessToken(user).accessToken();
    }

    @Test
    void getById_returns401_whenNoToken() throws Exception {
        mockMvc.perform(get("/api/orders/" + EXISTING_ORDER_ID))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void getById_returns200_whenFound() throws Exception {
        mockMvc.perform(get("/api/orders/" + EXISTING_ORDER_ID)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(EXISTING_ORDER_ID))
                .andExpect(jsonPath("$.orderNo").value(EXISTING_ORDER_NO))
                .andExpect(jsonPath("$.status").value(0));
    }

    @Test
    void getById_returns404_whenNotFound() throws Exception {
        mockMvc.perform(get("/api/orders/9999")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"));
    }

    @Test
    void getByOrderNo_returns200_whenFound() throws Exception {
        mockMvc.perform(get("/api/orders/by-no/" + EXISTING_ORDER_NO)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderNo").value(EXISTING_ORDER_NO));
    }

    @Test
    void getByOrderNo_returns404_whenNotFound() throws Exception {
        mockMvc.perform(get("/api/orders/by-no/NONEXISTENT")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"));
    }

    @Test
    void listMyOrders_returns200_withOrderList() throws Exception {
        mockMvc.perform(get("/api/orders/me")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value(EXISTING_ORDER_ID));
    }

    @TestConfiguration
    static class OrderControllerTestConfig {

        @Bean
        @Primary
        FakeOrderService fakeOrderService() {
            return new FakeOrderService();
        }
    }

    static class FakeOrderService implements OrderService {

        private static final OrderDTO SAMPLE = new OrderDTO(
                4001L, "TR202605130001", 2001L, 3002L, 1, 58000L, 0,
                LocalDateTime.of(2026, 5, 13, 11, 0, 0), null, null
        );

        @Override
        public OrderCreationResult createOrder(TicketRushMessage message) {
            return OrderCreationResult.created(EXISTING_ORDER_ID);
        }

        @Override
        public OrderDTO getById(Long orderId, Long userId) {
            if (orderId.equals(4001L) && userId.equals(1001L)) return SAMPLE;
            throw new OrderNotFoundException(orderId);
        }

        @Override
        public OrderDTO getByOrderNo(String orderNo, Long userId) {
            if (orderNo.equals("TR202605130001") && userId.equals(1001L)) return SAMPLE;
            throw new OrderNotFoundException(orderNo);
        }

        @Override
        public List<OrderDTO> listByUser(Long userId) {
            if (userId.equals(1001L)) return List.of(SAMPLE);
            return List.of();
        }
    }
}
