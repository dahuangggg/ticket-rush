package dev.dahuangggg.ticketrush.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import dev.dahuangggg.ticketrush.domain.rush.ReservationStatus;
import dev.dahuangggg.ticketrush.entity.InventoryReleaseIntent;
import dev.dahuangggg.ticketrush.entity.RushReservation;
import dev.dahuangggg.ticketrush.entity.TicketOrder;
import dev.dahuangggg.ticketrush.exception.OrderNotPendingException;
import dev.dahuangggg.ticketrush.infrastructure.redis.RedisKeyRegistry;
import dev.dahuangggg.ticketrush.mapper.InventoryReleaseIntentMapper;
import dev.dahuangggg.ticketrush.mapper.RushReservationMapper;
import dev.dahuangggg.ticketrush.mapper.TicketOrderMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 需要真实 MySQL + Redis 的 Order Lifecycle 集成测试。 */
@Tag("integration")
@SpringBootTest
class PaymentServiceTest {

    private static final Long USER_ID = 99002L;
    private static final Long SKU_ID = 3001L;
    private static final Long EVENT_ID = 2001L;
    private static final String RESERVATION_ID = "3001-paymenttestreservation";
    private static final Long POISON_USER_ID = 99003L;
    private static final Long POISON_SKU_ID = 3002L;
    private static final String POISON_RESERVATION_ID = "3002-missing-ledger-reservation";

    @Autowired private PaymentService paymentService;
    @Autowired private InventoryReleaseService releaseService;
    @Autowired private TicketOrderMapper orderMapper;
    @Autowired private RushReservationMapper reservationMapper;
    @Autowired private InventoryReleaseIntentMapper intentMapper;
    @Autowired private StringRedisTemplate redisTemplate;
    @Autowired private JdbcTemplate jdbcTemplate;

    private Long orderId;

    @BeforeEach
    void setUp() {
        clean();
        RushReservation reservation = RushReservation.builder()
                .reservationId(RESERVATION_ID)
                .userId(USER_ID)
                .eventId(EVENT_ID)
                .skuId(SKU_ID)
                .quantity(1)
                .unitPrice(38000L)
                .status(ReservationStatus.ORDER_CREATED.code())
                .build();
        reservationMapper.insert(reservation);

        TicketOrder order = TicketOrder.builder()
                .orderNo("TEST-ORDER-" + USER_ID)
                .reservationId(RESERVATION_ID)
                .userId(USER_ID)
                .eventId(EVENT_ID)
                .skuId(SKU_ID)
                .quantity(1)
                .totalAmount(38000L)
                .status(TicketOrder.STATUS_PENDING)
                .build();
        orderMapper.insert(order);
        orderId = order.getId();

        redisTemplate.opsForValue().set(RedisKeyRegistry.stockKey(SKU_ID), "10");
        redisTemplate.opsForSet().add(RedisKeyRegistry.orderUserKey(SKU_ID), String.valueOf(USER_ID));
        redisTemplate.opsForHash().putAll(
                RedisKeyRegistry.reservationKey(SKU_ID, RESERVATION_ID),
                Map.of("status", "ORDER_CREATED", "userId", String.valueOf(USER_ID)));
    }

    @AfterEach
    void tearDown() {
        clean();
        redisTemplate.delete(RedisKeyRegistry.stockKey(SKU_ID));
        redisTemplate.delete(RedisKeyRegistry.orderUserKey(SKU_ID));
        redisTemplate.delete(RedisKeyRegistry.reservationKey(SKU_ID, RESERVATION_ID));
    }

    @Test
    void cancel_commitsReleaseIntent_beforeRedisSideEffect() {
        paymentService.cancel(orderId, USER_ID);

        assertThat(orderMapper.selectById(orderId).getStatus()).isEqualTo(TicketOrder.STATUS_CANCELED);
        InventoryReleaseIntent intent = intentMapper.selectOne(
                new LambdaQueryWrapper<InventoryReleaseIntent>()
                        .eq(InventoryReleaseIntent::getReservationId, RESERVATION_ID));
        assertThat(intent.getStatus()).isEqualTo(InventoryReleaseIntent.STATUS_PENDING);
        assertThat(redisTemplate.opsForValue().get(RedisKeyRegistry.stockKey(SKU_ID))).isEqualTo("10");

        releaseService.processPending();
        releaseService.processPending();

        assertThat(intentMapper.selectById(intent.getId()).getStatus())
                .isEqualTo(InventoryReleaseIntent.STATUS_SUCCESS);
        assertThat(redisTemplate.opsForValue().get(RedisKeyRegistry.stockKey(SKU_ID))).isEqualTo("11");
        assertThat(redisTemplate.opsForSet().isMember(
                RedisKeyRegistry.orderUserKey(SKU_ID), String.valueOf(USER_ID))).isFalse();
    }

    @Test
    void pay_movesOrderAndLedgerToPaid() {
        paymentService.pay(orderId, USER_ID);

        assertThat(orderMapper.selectById(orderId).getStatus()).isEqualTo(TicketOrder.STATUS_PAID);
        RushReservation reservation = reservationMapper.selectOne(
                new LambdaQueryWrapper<RushReservation>()
                        .eq(RushReservation::getReservationId, RESERVATION_ID));
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.PAID.code());
        assertThat(redisTemplate.opsForHash().get(
                RedisKeyRegistry.reservationKey(SKU_ID, RESERVATION_ID), "status"))
                .isEqualTo("PAID");
        assertThatThrownBy(() -> paymentService.cancel(orderId, USER_ID))
                .isInstanceOf(OrderNotPendingException.class);
    }

    @Test
    void poisonTimeoutOrder_doesNotStarveLaterOrder() {
        TicketOrder poison = TicketOrder.builder()
                .orderNo("TEST-POISON-ORDER")
                .reservationId(POISON_RESERVATION_ID)
                .userId(POISON_USER_ID)
                .eventId(EVENT_ID)
                .skuId(POISON_SKU_ID)
                .quantity(1)
                .totalAmount(58000L)
                .status(TicketOrder.STATUS_PENDING)
                .build();
        orderMapper.insert(poison);
        jdbcTemplate.update("UPDATE tb_ticket_order SET create_time = ? WHERE id = ?",
                LocalDateTime.now().minusMinutes(30), poison.getId());
        jdbcTemplate.update("UPDATE tb_ticket_order SET create_time = ? WHERE id = ?",
                LocalDateTime.now().minusMinutes(20), orderId);

        paymentService.cancelTimeoutOrders();

        // 缺少 durable Reservation 的 poison row 在自己的事务内回滚。
        assertThat(orderMapper.selectById(poison.getId()).getStatus())
                .isEqualTo(TicketOrder.STATUS_PENDING);
        assertThat(intentMapper.selectCount(new LambdaQueryWrapper<InventoryReleaseIntent>()
                .eq(InventoryReleaseIntent::getReservationId, POISON_RESERVATION_ID))).isZero();

        // 同批较晚的正常订单仍完成状态迁移，并持久化 Release Intent。
        assertThat(orderMapper.selectById(orderId).getStatus()).isEqualTo(TicketOrder.STATUS_TIMEOUT);
        assertThat(intentMapper.selectCount(new LambdaQueryWrapper<InventoryReleaseIntent>()
                .eq(InventoryReleaseIntent::getReservationId, RESERVATION_ID))).isOne();
    }

    @Test
    void releaseFailureCounterAtomicallyReachesTerminalState() {
        InventoryReleaseIntent intent = InventoryReleaseIntent.builder()
                .reservationId(RESERVATION_ID)
                .orderId(orderId)
                .userId(USER_ID)
                .skuId(SKU_ID)
                .reason("TEST_FAILURE")
                .status(InventoryReleaseIntent.STATUS_PENDING)
                .retryCount(8)
                .build();
        intentMapper.insert(intent);

        assertThat(intentMapper.recordFailure(intent.getId(), "first", 9, 10)).isOne();
        assertThat(intentMapper.recordFailure(intent.getId(), "second", 9, 10)).isOne();

        InventoryReleaseIntent exhausted = intentMapper.selectById(intent.getId());
        assertThat(exhausted.getRetryCount()).isEqualTo(10);
        assertThat(exhausted.getStatus()).isEqualTo(InventoryReleaseIntent.STATUS_FAILED);
        assertThat(exhausted.getErrorMessage()).isEqualTo("second");
    }

    private void clean() {
        jdbcTemplate.update("DELETE FROM tb_inventory_release_intent WHERE user_id IN (?, ?)",
                USER_ID, POISON_USER_ID);
        jdbcTemplate.update("DELETE FROM tb_ticket_order WHERE user_id IN (?, ?)",
                USER_ID, POISON_USER_ID);
        jdbcTemplate.update("DELETE FROM tb_rush_reservation WHERE user_id IN (?, ?)",
                USER_ID, POISON_USER_ID);
    }
}
