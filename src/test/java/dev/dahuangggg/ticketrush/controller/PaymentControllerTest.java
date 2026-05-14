package dev.dahuangggg.ticketrush.controller;

import dev.dahuangggg.ticketrush.entity.User;
import dev.dahuangggg.ticketrush.exception.OrderNotFoundException;
import dev.dahuangggg.ticketrush.exception.OrderNotPendingException;
import dev.dahuangggg.ticketrush.security.JwtTokenService;
import dev.dahuangggg.ticketrush.service.PaymentService;
import org.junit.jupiter.api.BeforeEach;
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
class PaymentControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenService jwtTokenService;

    private String token;

    // FakePaymentService 中 orderId=5001 属于 userId=1001，可正常支付/取消
    // orderId=5002 属于 userId=1001，但状态非待支付（触发 OrderNotPendingException）
    private static final Long OWNER_USER_ID        = 1001L;
    private static final Long PENDING_ORDER_ID     = 5001L;
    private static final Long NON_PENDING_ORDER_ID = 5002L;

    @BeforeEach
    void setup() {
        User user = User.builder()
                .id(OWNER_USER_ID).phone("13800138000").nickName("测试用户").icon("").build();
        token = jwtTokenService.issueAccessToken(user).accessToken();
    }

    // ---- pay ----

    @Test
    void pay_returns401_whenNoToken() throws Exception {
        mockMvc.perform(post("/api/orders/" + PENDING_ORDER_ID + "/pay"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void pay_returns204_onSuccess() throws Exception {
        mockMvc.perform(post("/api/orders/" + PENDING_ORDER_ID + "/pay")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());
    }

    @Test
    void pay_returns404_whenOrderNotFound() throws Exception {
        mockMvc.perform(post("/api/orders/9999/pay")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"));
    }

    @Test
    void pay_returns400_whenOrderNotPending() throws Exception {
        mockMvc.perform(post("/api/orders/" + NON_PENDING_ORDER_ID + "/pay")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ORDER_NOT_PENDING"));
    }

    // ---- cancel ----

    @Test
    void cancel_returns401_whenNoToken() throws Exception {
        mockMvc.perform(post("/api/orders/" + PENDING_ORDER_ID + "/cancel"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void cancel_returns204_onSuccess() throws Exception {
        mockMvc.perform(post("/api/orders/" + PENDING_ORDER_ID + "/cancel")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());
    }

    @Test
    void cancel_returns404_whenOrderNotFound() throws Exception {
        mockMvc.perform(post("/api/orders/9999/cancel")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"));
    }

    @Test
    void cancel_returns400_whenOrderNotPending() throws Exception {
        mockMvc.perform(post("/api/orders/" + NON_PENDING_ORDER_ID + "/cancel")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ORDER_NOT_PENDING"));
    }

    @TestConfiguration
    static class PaymentControllerTestConfig {

        @Bean
        @Primary
        FakePaymentService fakePaymentService() {
            return new FakePaymentService();
        }
    }

    static class FakePaymentService implements PaymentService {

        @Override
        public void pay(Long orderId, Long userId) {
            if (orderId.equals(9999L)) throw new OrderNotFoundException(orderId);
            if (orderId.equals(NON_PENDING_ORDER_ID)) throw new OrderNotPendingException(orderId);
            // orderId=5001: 正常支付，什么都不做
        }

        @Override
        public void cancel(Long orderId, Long userId) {
            if (orderId.equals(9999L)) throw new OrderNotFoundException(orderId);
            if (orderId.equals(NON_PENDING_ORDER_ID)) throw new OrderNotPendingException(orderId);
            // orderId=5001: 正常取消，什么都不做
        }

        @Override
        public void cancelTimeoutOrders() {}
    }
}
