package dev.dahuangggg.ticketrush.service;

import dev.dahuangggg.ticketrush.exception.TicketSkuNotFoundException;
import dev.dahuangggg.ticketrush.infrastructure.redis.RedisKeyRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Tag("integration")
class StockInitServiceTest {

    @Autowired
    private StockInitService stockInitService;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static final String KEY = RedisKeyRegistry.stockKey(3001L);
    // SKU 3001 stock from the development Flyway seed
    private static final int SKU_3001_STOCK = 180;

    @BeforeEach
    void ensureClean() {
        resetDatabaseFixture();
        clearRedis();
    }

    @AfterEach
    void cleanup() {
        clearRedis();
        resetDatabaseFixture();
    }

    @Test
    void initStock_setsKeyAndReturnsTrue() {
        boolean result = stockInitService.initStock(3001L);

        assertThat(result).isTrue();
        assertThat(redisTemplate.opsForValue().get(KEY)).isNotNull();
    }

    @Test
    void initStock_returnsFalseWhenAlreadyInitialized() {
        stockInitService.initStock(3001L);

        // second call must not overwrite
        boolean second = stockInitService.initStock(3001L);

        assertThat(second).isFalse();
    }

    @Test
    void initStock_throwsNotFoundWhenSkuMissing() {
        assertThatThrownBy(() -> stockInitService.initStock(9999L))
                .isInstanceOf(TicketSkuNotFoundException.class);
    }

    @Test
    void getAvailableStock_returnsValueWhenInitialized() {
        stockInitService.initStock(3001L);

        Integer stock = stockInitService.getAvailableStock(3001L);

        assertThat(stock).isEqualTo(SKU_3001_STOCK);
    }

    @Test
    void getAvailableStock_returnsNullWhenNotInitialized() {
        Integer stock = stockInitService.getAvailableStock(3001L);

        assertThat(stock).isNull();
    }

    private void resetDatabaseFixture() {
        jdbcTemplate.update("""
                UPDATE tb_ticket_sku
                   SET stock = ?, stock_initialized = 0
                 WHERE id = 3001
                """, SKU_3001_STOCK);
    }

    private void clearRedis() {
        redisTemplate.delete(KEY);
        redisTemplate.delete(RedisKeyRegistry.orderUserKey(3001L));
        redisTemplate.delete(RedisKeyRegistry.skuMetadataKey(3001L));
        redisTemplate.opsForSet().remove(
                RedisKeyRegistry.reservationOutboxSkuRegistryKey(), "3001");
    }
}
