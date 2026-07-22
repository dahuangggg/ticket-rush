package dev.dahuangggg.ticketrush.controller;

import dev.dahuangggg.ticketrush.dto.rush.TicketRushRequest;
import dev.dahuangggg.ticketrush.dto.rush.TicketRushResponse;
import dev.dahuangggg.ticketrush.entity.User;
import dev.dahuangggg.ticketrush.exception.DuplicateOrderException;
import dev.dahuangggg.ticketrush.exception.SoldOutException;
import dev.dahuangggg.ticketrush.exception.TicketSkuUnavailableException;
import dev.dahuangggg.ticketrush.security.JwtTokenService;
import dev.dahuangggg.ticketrush.service.TicketRushService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class TicketRushControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenService jwtTokenService;

    private String token;

    @BeforeEach
    void setup() {
        User user = User.builder()
                .id(1001L).phone("13800138000").nickName("测试用户").icon("").build();
        token = jwtTokenService.issueAccessToken(user).accessToken();
    }

    @Test
    void rush_returns401_whenNoToken() throws Exception {
        mockMvc.perform(post("/api/ticket-rush/requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"eventId":2001,"skuId":3001,"quantity":1}
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void rush_returnsReservation_whenSuccess() throws Exception {
        mockMvc.perform(post("/api/ticket-rush/requests")
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", "controller-test-request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"eventId":2001,"skuId":3001,"quantity":1}
                                """))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("RESERVED"))
                .andExpect(jsonPath("$.reservationId").value("3001-testreservation"));
    }

    @Test
    void getReservation_returnsCurrentStatus() throws Exception {
        mockMvc.perform(get("/api/ticket-rush/reservations/3001-testreservation")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ORDER_CREATED"))
                .andExpect(jsonPath("$.orderId").value(4001L));
    }

    @Test
    void rush_returns400_whenIdempotencyKeyIsMissing() throws Exception {
        mockMvc.perform(post("/api/ticket-rush/requests")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"eventId":2001,"skuId":3001,"quantity":1}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
    }

    @Test
    void rush_returns400SoldOut_whenSoldOut() throws Exception {
        mockMvc.perform(post("/api/ticket-rush/requests")
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", "sold-out-request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"eventId":2001,"skuId":3002,"quantity":1}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SOLD_OUT"));
    }

    @Test
    void rush_returns400Duplicate_whenDuplicate() throws Exception {
        mockMvc.perform(post("/api/ticket-rush/requests")
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", "duplicate-request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"eventId":2001,"skuId":3003,"quantity":1}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("DUPLICATE_ORDER"));
    }

    @Test
    void rush_returns400Unavailable_whenSkuCannotRush() throws Exception {
        mockMvc.perform(post("/api/ticket-rush/requests")
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", "unavailable-request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"eventId":2001,"skuId":3004,"quantity":1}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("TICKET_SKU_UNAVAILABLE"));
    }

    @Test
    void rush_returns400_whenQuantityExceedsLimit() throws Exception {
        mockMvc.perform(post("/api/ticket-rush/requests")
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", "invalid-quantity-request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"eventId":2001,"skuId":3001,"quantity":2}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
    }

    @TestConfiguration
    static class TicketRushControllerTestConfig {

        @Bean
        @Primary
        FakeTicketRushService fakeTicketRushService() {
            return new FakeTicketRushService();
        }
    }

    static class FakeTicketRushService implements TicketRushService {

        @Override
        public TicketRushResponse rush(Long userId, TicketRushRequest request, String idempotencyKey) {
            // 3002 → 售罄，3003 → 重复，3004 → 当前不可抢，其余 → 成功
            if (request.skuId().equals(3002L)) throw new SoldOutException(3002L);
            if (request.skuId().equals(3003L)) throw new DuplicateOrderException(3003L);
            if (request.skuId().equals(3004L)) throw new TicketSkuUnavailableException(3004L);
            return new TicketRushResponse("3001-testreservation", "RESERVED", null);
        }

        @Override
        public TicketRushResponse getReservation(String reservationId, Long userId) {
            return new TicketRushResponse(reservationId, "ORDER_CREATED", 4001L);
        }
    }
}
