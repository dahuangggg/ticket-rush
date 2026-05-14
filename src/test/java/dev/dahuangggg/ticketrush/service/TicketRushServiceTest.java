package dev.dahuangggg.ticketrush.service;

import dev.dahuangggg.ticketrush.dto.rush.TicketRushRequest;
import dev.dahuangggg.ticketrush.exception.DuplicateOrderException;
import dev.dahuangggg.ticketrush.exception.SoldOutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class TicketRushServiceTest {

    @Autowired
    private TicketRushService ticketRushService;

    @Autowired
    private StringRedisTemplate redisTemplate;

    private static final Long USER_ID  = 1001L;
    private static final Long SKU_ID   = 3001L;
    private static final Long EVENT_ID = 2001L;
    private static final String STOCK_KEY = "ticket:stock:3001";
    private static final String ORDER_KEY = "ticket:order:user:3001";

    @BeforeEach
    void setup() {
        redisTemplate.opsForValue().set(STOCK_KEY, "5");
        redisTemplate.delete(ORDER_KEY);
    }

    @AfterEach
    void cleanup() {
        redisTemplate.delete(STOCK_KEY);
        redisTemplate.delete(ORDER_KEY);
    }

    @Test
    void rush_returnsQueued_andDecrementStock_andRecordsUser() {
        var resp = ticketRushService.rush(USER_ID, new TicketRushRequest(EVENT_ID, SKU_ID, 1));

        assertThat(resp.status()).isEqualTo("QUEUED");
        assertThat(redisTemplate.opsForValue().get(STOCK_KEY)).isEqualTo("4");
        assertThat(redisTemplate.opsForSet().isMember(ORDER_KEY, String.valueOf(USER_ID))).isTrue();
    }

    @Test
    void rush_throwsDuplicateOrder_whenSameUserRushesAgain() {
        ticketRushService.rush(USER_ID, new TicketRushRequest(EVENT_ID, SKU_ID, 1));

        assertThatThrownBy(() -> ticketRushService.rush(USER_ID, new TicketRushRequest(EVENT_ID, SKU_ID, 1)))
                .isInstanceOf(DuplicateOrderException.class);
    }

    @Test
    void rush_throwsSoldOut_whenStockIsZero() {
        redisTemplate.opsForValue().set(STOCK_KEY, "0");

        assertThatThrownBy(() -> ticketRushService.rush(USER_ID, new TicketRushRequest(EVENT_ID, SKU_ID, 1)))
                .isInstanceOf(SoldOutException.class);
    }

    @Test
    void rush_throwsSoldOut_whenStockKeyDoesNotExist() {
        redisTemplate.delete(STOCK_KEY);

        assertThatThrownBy(() -> ticketRushService.rush(USER_ID, new TicketRushRequest(EVENT_ID, SKU_ID, 1)))
                .isInstanceOf(SoldOutException.class);
    }
}
