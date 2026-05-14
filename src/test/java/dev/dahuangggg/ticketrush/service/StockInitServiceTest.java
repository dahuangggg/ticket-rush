package dev.dahuangggg.ticketrush.service;

import dev.dahuangggg.ticketrush.exception.TicketSkuNotFoundException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class StockInitServiceTest {

    @Autowired
    private StockInitService stockInitService;

    @Autowired
    private StringRedisTemplate redisTemplate;

    private static final String KEY = "ticket:stock:3001";
    // SKU 3001 seed stock from 002_seed_data.sql
    private static final int SKU_3001_STOCK = 180;

    @BeforeEach
    void ensureClean() {
        redisTemplate.delete(KEY);
    }

    @AfterEach
    void cleanup() {
        redisTemplate.delete(KEY);
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
}
