package dev.dahuangggg.ticketrush.service;

import dev.dahuangggg.ticketrush.domain.rush.ReleaseResult;
import dev.dahuangggg.ticketrush.dto.rush.TicketRushRequest;
import dev.dahuangggg.ticketrush.dto.rush.TicketRushResponse;
import dev.dahuangggg.ticketrush.exception.DuplicateOrderException;
import dev.dahuangggg.ticketrush.exception.SoldOutException;
import dev.dahuangggg.ticketrush.exception.TicketSkuUnavailableException;
import dev.dahuangggg.ticketrush.infrastructure.redis.RedisKeyRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 需要真实 MySQL + Redis 的 Reservation Lua 集成测试。 */
@Tag("integration")
@SpringBootTest
class TicketRushServiceTest {

    private static final Long USER_ID = 99101L;
    private static final Long EVENT_ID = 2001L;
    private static final Long SKU_ID = 3001L;

    @Autowired private TicketRushService ticketRushService;
    @Autowired private StockInitService stockInitService;
    @Autowired private ReservationReleaseStore releaseStore;
    @Autowired private ReservationOutbox reservationOutbox;
    @Autowired private StringRedisTemplate redisTemplate;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private Clock clock;

    private SkuSnapshot originalSku;

    @BeforeEach
    void setUp() {
        originalSku = loadSkuSnapshot();
        clearRedis();
        clearPersistentTestRows();
        LocalDateTime now = LocalDateTime.now(clock);
        jdbcTemplate.update("""
                UPDATE tb_ticket_sku
                   SET stock = 5, status = 1, event_id = ?, price = 38000,
                       stock_initialized = 0, sale_start_time = ?, sale_end_time = ?
                 WHERE id = ?
                """, EVENT_ID, now.minusMinutes(5), now.plusMinutes(30), SKU_ID);
        stockInitService.initStock(SKU_ID);
    }

    @AfterEach
    void tearDown() {
        clearRedis();
        clearPersistentTestRows();
        restoreSkuSnapshot();
    }

    private void clearPersistentTestRows() {
        jdbcTemplate.update("DELETE FROM tb_inventory_release_intent WHERE user_id = ?", USER_ID);
        jdbcTemplate.update("DELETE FROM tb_ticket_order_msg WHERE user_id = ?", USER_ID);
        jdbcTemplate.update("DELETE FROM tb_ticket_order WHERE user_id = ?", USER_ID);
        jdbcTemplate.update("DELETE FROM tb_rush_reservation WHERE user_id = ?", USER_ID);
    }

    private SkuSnapshot loadSkuSnapshot() {
        return jdbcTemplate.queryForObject("""
                SELECT event_id, name, price, stock, stock_initialized,
                       sale_start_time, sale_end_time, limit_per_user, status, deleted,
                       create_time, update_time
                  FROM tb_ticket_sku
                 WHERE id = ?
                """, (rs, rowNum) -> new SkuSnapshot(
                rs.getLong("event_id"),
                rs.getString("name"),
                rs.getLong("price"),
                rs.getInt("stock"),
                rs.getInt("stock_initialized"),
                rs.getTimestamp("sale_start_time").toLocalDateTime(),
                rs.getTimestamp("sale_end_time").toLocalDateTime(),
                rs.getInt("limit_per_user"),
                rs.getInt("status"),
                rs.getInt("deleted"),
                rs.getTimestamp("create_time").toLocalDateTime(),
                rs.getTimestamp("update_time").toLocalDateTime()),
                SKU_ID);
    }

    private void restoreSkuSnapshot() {
        if (originalSku == null) {
            return;
        }
        jdbcTemplate.update("""
                UPDATE tb_ticket_sku
                   SET event_id = ?, name = ?, price = ?, stock = ?, stock_initialized = ?,
                       sale_start_time = ?, sale_end_time = ?, limit_per_user = ?, status = ?,
                       deleted = ?, create_time = ?, update_time = ?
                 WHERE id = ?
                """,
                originalSku.eventId(), originalSku.name(), originalSku.price(), originalSku.stock(),
                originalSku.stockInitialized(), originalSku.saleStartTime(), originalSku.saleEndTime(),
                originalSku.limitPerUser(), originalSku.status(), originalSku.deleted(),
                originalSku.createTime(), originalSku.updateTime(), SKU_ID);
    }

    @Test
    void reserve_writesStockBuyerReservationAndOutboxAtomically() {
        TicketRushResponse response = rush("request-1");

        assertThat(response.status()).isEqualTo("RESERVED");
        assertThat(response.reservationId()).startsWith(SKU_ID + "-");
        assertThat(redisTemplate.opsForValue().get(RedisKeyRegistry.stockKey(SKU_ID))).isEqualTo("4");
        assertThat(redisTemplate.opsForSet().isMember(
                RedisKeyRegistry.orderUserKey(SKU_ID), String.valueOf(USER_ID))).isTrue();
        assertThat(redisTemplate.opsForHash().get(
                RedisKeyRegistry.reservationKey(SKU_ID, response.reservationId()), "status"))
                .isEqualTo("RESERVED");
        assertThat(redisTemplate.opsForStream().size(RedisKeyRegistry.reservationOutboxKey(SKU_ID)))
                .isOne();
    }

    @Test
    void sameIdempotencyKey_returnsSameReservationWithoutSecondDecrement() {
        TicketRushResponse first = rush("stable-request");
        TicketRushResponse second = rush("stable-request");

        assertThat(second.reservationId()).isEqualTo(first.reservationId());
        assertThat(redisTemplate.opsForValue().get(RedisKeyRegistry.stockKey(SKU_ID))).isEqualTo("4");
    }

    @Test
    void differentRequestFromSameUser_isRejected() {
        rush("request-a");

        assertThatThrownBy(() -> rush("request-b"))
                .isInstanceOf(DuplicateOrderException.class);
    }

    @Test
    void soldOutAndWrongEvent_doNotCreateReservation() {
        redisTemplate.opsForValue().set(RedisKeyRegistry.stockKey(SKU_ID), "0");
        assertThatThrownBy(() -> rush("sold-out"))
                .isInstanceOf(SoldOutException.class);

        redisTemplate.opsForValue().set(RedisKeyRegistry.stockKey(SKU_ID), "5");
        assertThatThrownBy(() -> ticketRushService.rush(
                USER_ID, new TicketRushRequest(9999L, SKU_ID, 1), "wrong-event"))
                .isInstanceOf(TicketSkuUnavailableException.class);
    }

    @Test
    void release_isIdempotentByReservationId() {
        TicketRushResponse response = rush("release-request");

        assertThat(releaseStore.release(response.reservationId(), SKU_ID, USER_ID))
                .isEqualTo(ReleaseResult.RELEASED);
        assertThat(releaseStore.release(response.reservationId(), SKU_ID, USER_ID))
                .isEqualTo(ReleaseResult.ALREADY_RELEASED);
        assertThat(redisTemplate.opsForValue().get(RedisKeyRegistry.stockKey(SKU_ID))).isEqualTo("5");
    }

    @Test
    void release_failsClosed_whenStockKeyIsMissing() {
        TicketRushResponse response = rush("missing-stock-release");
        redisTemplate.delete(RedisKeyRegistry.stockKey(SKU_ID));

        assertThatThrownBy(() -> releaseStore.release(response.reservationId(), SKU_ID, USER_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("refusing an unsafe increment");
        assertThat(redisTemplate.opsForHash().get(
                RedisKeyRegistry.reservationKey(SKU_ID, response.reservationId()), "status"))
                .isEqualTo("RESERVED");
        assertThat(redisTemplate.opsForSet().isMember(
                RedisKeyRegistry.orderUserKey(SKU_ID), String.valueOf(USER_ID))).isTrue();
    }

    @Test
    void reserve_failsBeforeMutation_whenOutboxHasWrongRedisType() {
        redisTemplate.opsForValue().set(RedisKeyRegistry.reservationOutboxKey(SKU_ID), "corrupt");

        assertThatThrownBy(() -> rush("corrupt-outbox"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("reservation state is corrupt");
        assertThat(redisTemplate.opsForValue().get(RedisKeyRegistry.stockKey(SKU_ID))).isEqualTo("5");
        assertThat(redisTemplate.opsForSet().isMember(
                RedisKeyRegistry.orderUserKey(SKU_ID), String.valueOf(USER_ID))).isFalse();
    }

    @Test
    void reserve_failsBeforeMutation_whenMetadataIntegerExceedsSafeRange() {
        String metadataKey = RedisKeyRegistry.skuMetadataKey(SKU_ID);
        Map<Object, Object> original = redisTemplate.opsForHash().entries(metadataKey);

        for (String field : List.of("saleStart", "saleEnd", "cleanupAt", "unitPrice")) {
            redisTemplate.opsForHash().put(
                    metadataKey, field, "999999999999999999999999999999");

            assertThatThrownBy(() -> rush("oversized-" + field))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("reservation state is corrupt");
            assertNoReservationMutation();

            redisTemplate.opsForHash().put(metadataKey, field, original.get(field));
        }
    }

    @Test
    void reserve_failsBeforeInventoryMutation_whenOutboxCannotGenerateAnotherId() {
        String outboxKey = RedisKeyRegistry.reservationOutboxKey(SKU_ID);
        redisTemplate.opsForStream().add(MapRecord
                .create(outboxKey, Map.of("marker", "exhausted"))
                .withId(RecordId.of("18446744073709551615-18446744073709551615")));

        assertThatThrownBy(() -> rush("exhausted-outbox"))
                .isInstanceOf(RuntimeException.class);
        assertThat(redisTemplate.opsForValue().get(RedisKeyRegistry.stockKey(SKU_ID))).isEqualTo("5");
        assertThat(redisTemplate.opsForSet().isMember(
                RedisKeyRegistry.orderUserKey(SKU_ID), String.valueOf(USER_ID))).isFalse();
        assertThat(redisTemplate.keys("ticket:{" + SKU_ID + "}:reservation:*")).isEmpty();
        assertThat(redisTemplate.keys("ticket:{" + SKU_ID + "}:request:*")).isEmpty();
        assertThat(redisTemplate.opsForStream().size(outboxKey)).isOne();
    }

    @Test
    void poisonJournalEntryIsQuarantinedSoBatchSizeOneCanReachHealthyReservation() {
        String outboxKey = RedisKeyRegistry.reservationOutboxKey(SKU_ID);
        RecordId poisonId = redisTemplate.opsForStream().add(
                MapRecord.create(outboxKey, Map.of("reservationId", "malformed")));
        TicketRushResponse healthy = rush("healthy-after-poison");

        assertThat(reservationOutbox.pending(SKU_ID, 1)).isEmpty();
        assertThat(reservationOutbox.pending(SKU_ID, 1))
                .singleElement()
                .satisfies(record -> assertThat(record.reservationId())
                        .isEqualTo(healthy.reservationId()));

        assertThat(redisTemplate.opsForStream().size(outboxKey)).isOne();
        var quarantined = redisTemplate.opsForStream().range(
                RedisKeyRegistry.reservationOutboxQuarantineKey(SKU_ID),
                org.springframework.data.domain.Range.unbounded());
        assertThat(quarantined).singleElement().satisfies(record -> {
            assertThat(record.getValue().get("sourceStreamId")).isEqualTo(poisonId.getValue());
            assertThat(record.getValue()).containsKeys("reason", "rawFieldsCmsgpack");
        });
    }

    @Test
    void release_failsBeforeMutation_whenStockIsNotAnInteger() {
        TicketRushResponse response = rush("corrupt-release-stock");
        redisTemplate.opsForValue().set(RedisKeyRegistry.stockKey(SKU_ID), "not-an-integer");

        assertThatThrownBy(() -> releaseStore.release(response.reservationId(), SKU_ID, USER_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("refusing a partial release");
        assertThat(redisTemplate.opsForHash().get(
                RedisKeyRegistry.reservationKey(SKU_ID, response.reservationId()), "status"))
                .isEqualTo("RESERVED");
        assertThat(redisTemplate.opsForSet().isMember(
                RedisKeyRegistry.orderUserKey(SKU_ID), String.valueOf(USER_ID))).isTrue();
    }

    private TicketRushResponse rush(String idempotencyKey) {
        return ticketRushService.rush(
                USER_ID, new TicketRushRequest(EVENT_ID, SKU_ID, 1), idempotencyKey);
    }

    private void assertNoReservationMutation() {
        assertThat(redisTemplate.opsForValue().get(RedisKeyRegistry.stockKey(SKU_ID))).isEqualTo("5");
        assertThat(redisTemplate.opsForSet().isMember(
                RedisKeyRegistry.orderUserKey(SKU_ID), String.valueOf(USER_ID))).isFalse();
        assertThat(redisTemplate.hasKey(RedisKeyRegistry.reservationOutboxKey(SKU_ID))).isFalse();
        assertThat(redisTemplate.keys("ticket:{" + SKU_ID + "}:reservation:*")).isEmpty();
        assertThat(redisTemplate.keys("ticket:{" + SKU_ID + "}:request:*")).isEmpty();
    }

    private void clearRedis() {
        redisTemplate.delete(RedisKeyRegistry.stockKey(SKU_ID));
        redisTemplate.delete(RedisKeyRegistry.orderUserKey(SKU_ID));
        redisTemplate.delete(RedisKeyRegistry.skuMetadataKey(SKU_ID));
        redisTemplate.delete(RedisKeyRegistry.reservationOutboxKey(SKU_ID));
        redisTemplate.delete(RedisKeyRegistry.reservationOutboxQuarantineKey(SKU_ID));
        redisTemplate.opsForSet().remove(
                RedisKeyRegistry.reservationOutboxSkuRegistryKey(), String.valueOf(SKU_ID));
        var keys = redisTemplate.keys("ticket:{" + SKU_ID + "}:reservation:*");
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
        keys = redisTemplate.keys("ticket:{" + SKU_ID + "}:request:*");
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
    }

    private record SkuSnapshot(
            Long eventId,
            String name,
            Long price,
            Integer stock,
            Integer stockInitialized,
            LocalDateTime saleStartTime,
            LocalDateTime saleEndTime,
            Integer limitPerUser,
            Integer status,
            Integer deleted,
            LocalDateTime createTime,
            LocalDateTime updateTime) {
    }
}
