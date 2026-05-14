package dev.dahuangggg.ticketrush.service;

import dev.dahuangggg.ticketrush.dto.rush.TicketRushRequest;
import dev.dahuangggg.ticketrush.exception.DuplicateOrderException;
import dev.dahuangggg.ticketrush.exception.KafkaPublishException;
import dev.dahuangggg.ticketrush.exception.SoldOutException;
import dev.dahuangggg.ticketrush.exception.TicketSkuUnavailableException;
import dev.dahuangggg.ticketrush.infrastructure.mq.TicketRushMessage;
import dev.dahuangggg.ticketrush.infrastructure.mq.TicketRushProducer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class TicketRushServiceTest {

    @Autowired
    private TicketRushService ticketRushService;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private FakeTicketRushProducer fakeTicketRushProducer;

    private static final Long USER_ID  = 99101L;
    private static final Long SKU_ID   = 3901L;
    private static final Long UNAVAILABLE_SKU_ID = 3906L;
    private static final Long EVENT_ID = 2001L;
    private static final String STOCK_KEY = "ticket:stock:3901";
    private static final String ORDER_KEY = "ticket:order:user:3901";
    private static final String UNAVAILABLE_STOCK_KEY = "ticket:stock:3906";
    private static final String UNAVAILABLE_ORDER_KEY = "ticket:order:user:3906";

    @BeforeEach
    void setup() {
        jdbcTemplate.update("""
                INSERT INTO tb_ticket_sku (
                    id, event_id, name, price, stock, sale_start_time, sale_end_time,
                    limit_per_user, status, deleted, create_time, update_time
                ) VALUES
                    (?, ?, '测试可售票档', 38000, 50, NOW() - INTERVAL 1 DAY, NOW() + INTERVAL 1 DAY, 1, 1, 0, NOW(), NOW()),
                    (?, ?, '测试不可售票档', 38000, 50, NOW() - INTERVAL 1 DAY, NOW() + INTERVAL 1 DAY, 1, 0, 0, NOW(), NOW())
                ON DUPLICATE KEY UPDATE
                    event_id = VALUES(event_id),
                    sale_start_time = VALUES(sale_start_time),
                    sale_end_time = VALUES(sale_end_time),
                    status = VALUES(status),
                    deleted = VALUES(deleted),
                    update_time = VALUES(update_time)
                """, SKU_ID, EVENT_ID, UNAVAILABLE_SKU_ID, EVENT_ID);
        jdbcTemplate.update("DELETE FROM tb_ticket_order WHERE user_id = ?", USER_ID);
        jdbcTemplate.update("DELETE FROM tb_ticket_order_msg WHERE user_id = ?", USER_ID);
        fakeTicketRushProducer.failNextSend = false;
        redisTemplate.opsForValue().set(STOCK_KEY, "5");
        redisTemplate.delete(ORDER_KEY);
        redisTemplate.opsForValue().set(UNAVAILABLE_STOCK_KEY, "10");
        redisTemplate.delete(UNAVAILABLE_ORDER_KEY);
    }

    @AfterEach
    void cleanup() {
        redisTemplate.delete(STOCK_KEY);
        redisTemplate.delete(ORDER_KEY);
        redisTemplate.delete(UNAVAILABLE_STOCK_KEY);
        redisTemplate.delete(UNAVAILABLE_ORDER_KEY);
        jdbcTemplate.update("DELETE FROM tb_ticket_order WHERE user_id = ?", USER_ID);
        jdbcTemplate.update("DELETE FROM tb_ticket_order_msg WHERE user_id = ?", USER_ID);
        jdbcTemplate.update("DELETE FROM tb_ticket_sku WHERE id IN (?, ?)", SKU_ID, UNAVAILABLE_SKU_ID);
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

    @Test
    void rush_throwsUnavailable_andDoesNotDecrementStock_whenSkuDoesNotBelongToEvent() {
        assertThatThrownBy(() -> ticketRushService.rush(USER_ID, new TicketRushRequest(9999L, SKU_ID, 1)))
                .isInstanceOf(TicketSkuUnavailableException.class);

        assertThat(redisTemplate.opsForValue().get(STOCK_KEY)).isEqualTo("5");
        assertThat(redisTemplate.opsForSet().isMember(ORDER_KEY, String.valueOf(USER_ID))).isFalse();
    }

    @Test
    void rush_throwsUnavailable_andDoesNotDecrementStock_whenSkuNotOnSale() {
        assertThatThrownBy(() -> ticketRushService.rush(
                USER_ID, new TicketRushRequest(EVENT_ID, UNAVAILABLE_SKU_ID, 1)))
                .isInstanceOf(TicketSkuUnavailableException.class);

        assertThat(redisTemplate.opsForValue().get(UNAVAILABLE_STOCK_KEY)).isEqualTo("10");
        assertThat(redisTemplate.opsForSet().isMember(UNAVAILABLE_ORDER_KEY, String.valueOf(USER_ID))).isFalse();
    }

    @Test
    void rush_rollsBackRedis_whenKafkaSendFails() {
        fakeTicketRushProducer.failNextSend = true;

        assertThatThrownBy(() -> ticketRushService.rush(USER_ID, new TicketRushRequest(EVENT_ID, SKU_ID, 1)))
                .isInstanceOf(KafkaPublishException.class);

        assertThat(redisTemplate.opsForValue().get(STOCK_KEY)).isEqualTo("5");
        assertThat(redisTemplate.opsForSet().isMember(ORDER_KEY, String.valueOf(USER_ID))).isFalse();
    }

    @TestConfiguration
    static class TicketRushServiceTestConfig {

        @Bean
        @Primary
        FakeTicketRushProducer fakeTicketRushProducer() {
            return new FakeTicketRushProducer();
        }
    }

    static class FakeTicketRushProducer extends TicketRushProducer {

        private boolean failNextSend;

        FakeTicketRushProducer() {
            super(null, null);
        }

        @Override
        public void send(TicketRushMessage message) {
            if (failNextSend) {
                throw new KafkaPublishException("测试 Kafka 发送失败", new RuntimeException("boom"));
            }
        }
    }
}
