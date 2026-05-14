package dev.dahuangggg.ticketrush.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import dev.dahuangggg.ticketrush.entity.TicketOrder;
import dev.dahuangggg.ticketrush.entity.TicketRollbackTask;
import dev.dahuangggg.ticketrush.exception.OrderNotFoundException;
import dev.dahuangggg.ticketrush.exception.OrderNotPendingException;
import dev.dahuangggg.ticketrush.mapper.TicketOrderMapper;
import dev.dahuangggg.ticketrush.mapper.TicketRollbackTaskMapper;
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

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class PaymentServiceTest {

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private TicketOrderMapper ticketOrderMapper;

    @Autowired
    private TicketRollbackTaskMapper ticketRollbackTaskMapper;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private FakeRedisRollbackService fakeRedisRollbackService;

    // 测试专用 userId，与其他测试用例不冲突
    private static final Long TEST_USER_ID  = 99002L;
    private static final Long TEST_SKU_ID   = 3001L;
    private static final Long TEST_EVENT_ID = 2001L;

    private Long testOrderId;

    @BeforeEach
    void setup() {
        // 物理删除旧数据，避免唯一索引冲突
        jdbcTemplate.update("DELETE FROM tb_ticket_order WHERE user_id = ?", TEST_USER_ID);
        jdbcTemplate.update("DELETE FROM tb_ticket_rollback_task WHERE user_id = ?", TEST_USER_ID);
        fakeRedisRollbackService.failNextRollback = false;

        // 初始化 Redis：向 stock 写入已知值，向 user set 加入测试 userId
        redisTemplate.opsForValue().set("ticket:stock:" + TEST_SKU_ID, "10");
        redisTemplate.opsForSet().add("ticket:order:user:" + TEST_SKU_ID, String.valueOf(TEST_USER_ID));

        // 插入一条待支付订单
        TicketOrder order = TicketOrder.builder()
                .orderNo("TEST-ORDER-" + TEST_USER_ID)
                .userId(TEST_USER_ID)
                .eventId(TEST_EVENT_ID)
                .skuId(TEST_SKU_ID)
                .quantity(1)
                .totalAmount(38000L)
                .status(TicketOrder.STATUS_PENDING)
                .build();
        ticketOrderMapper.insert(order);
        testOrderId = order.getId();
    }

    @AfterEach
    void cleanup() {
        jdbcTemplate.update("DELETE FROM tb_ticket_order WHERE user_id = ?", TEST_USER_ID);
        jdbcTemplate.update("DELETE FROM tb_ticket_rollback_task WHERE user_id = ?", TEST_USER_ID);
        redisTemplate.delete("ticket:stock:" + TEST_SKU_ID);
        redisTemplate.opsForSet().remove("ticket:order:user:" + TEST_SKU_ID, String.valueOf(TEST_USER_ID));
    }

    @Test
    void pay_changesStatusTo1() {
        paymentService.pay(testOrderId, TEST_USER_ID);

        TicketOrder order = ticketOrderMapper.selectById(testOrderId);
        assertThat(order.getStatus()).isEqualTo(TicketOrder.STATUS_PAID);
        assertThat(order.getPayTime()).isNotNull();
    }

    @Test
    void pay_throws_whenOrderNotFound() {
        assertThatThrownBy(() -> paymentService.pay(9999999L, TEST_USER_ID))
                .isInstanceOf(OrderNotFoundException.class);
    }

    @Test
    void pay_throws_whenOrderNotPending() {
        // 先支付一次变为 status=1
        paymentService.pay(testOrderId, TEST_USER_ID);

        // 再次支付应抛出 OrderNotPendingException
        assertThatThrownBy(() -> paymentService.pay(testOrderId, TEST_USER_ID))
                .isInstanceOf(OrderNotPendingException.class);
    }

    @Test
    void cancel_changesStatusTo2_andRollsBackRedis() {
        paymentService.cancel(testOrderId, TEST_USER_ID);

        // 订单状态应变为已取消
        TicketOrder order = ticketOrderMapper.selectById(testOrderId);
        assertThat(order.getStatus()).isEqualTo(TicketOrder.STATUS_CANCELED);
        assertThat(order.getCancelTime()).isNotNull();

        // Redis 库存应+1（10 → 11）
        String stock = redisTemplate.opsForValue().get("ticket:stock:" + TEST_SKU_ID);
        assertThat(stock).isEqualTo("11");

        // userId 应从 user set 中移除
        Boolean isMember = redisTemplate.opsForSet()
                .isMember("ticket:order:user:" + TEST_SKU_ID, String.valueOf(TEST_USER_ID));
        assertThat(isMember).isFalse();
    }

    @Test
    void cancel_throws_whenOrderNotPending() {
        paymentService.pay(testOrderId, TEST_USER_ID);

        assertThatThrownBy(() -> paymentService.cancel(testOrderId, TEST_USER_ID))
                .isInstanceOf(OrderNotPendingException.class);
    }

    @Test
    void cancelTimeoutOrders_cancelsOrdersOlderThan15Min() {
        // 将订单的 create_time 改为 20 分钟前，使其触发超时
        LocalDateTime twentyMinAgo = LocalDateTime.now().minusMinutes(20);
        jdbcTemplate.update("UPDATE tb_ticket_order SET create_time = ? WHERE id = ?",
                twentyMinAgo, testOrderId);

        paymentService.cancelTimeoutOrders();

        TicketOrder order = ticketOrderMapper.selectById(testOrderId);
        assertThat(order.getStatus()).isEqualTo(TicketOrder.STATUS_TIMEOUT);
        assertThat(order.getCancelTime()).isNotNull();

        // Redis 库存应+1（10 → 11）
        String stock = redisTemplate.opsForValue().get("ticket:stock:" + TEST_SKU_ID);
        assertThat(stock).isEqualTo("11");
    }

    @Test
    void cancelTimeoutOrders_doesNotAffectRecentOrders() {
        // 订单刚刚创建（不到 15 分钟），不应被超时取消
        paymentService.cancelTimeoutOrders();

        TicketOrder order = ticketOrderMapper.selectById(testOrderId);
        assertThat(order.getStatus()).isEqualTo(TicketOrder.STATUS_PENDING);
    }

    @Test
    void cancel_recordsRollbackTask_whenRedisRollbackFails() {
        fakeRedisRollbackService.failNextRollback = true;

        paymentService.cancel(testOrderId, TEST_USER_ID);

        TicketOrder order = ticketOrderMapper.selectById(testOrderId);
        assertThat(order.getStatus()).isEqualTo(TicketOrder.STATUS_CANCELED);

        TicketRollbackTask task = ticketRollbackTaskMapper.selectOne(
                new LambdaQueryWrapper<TicketRollbackTask>()
                        .eq(TicketRollbackTask::getOrderId, testOrderId));
        assertThat(task).isNotNull();
        assertThat(task.getStatus()).isEqualTo(TicketRollbackTask.STATUS_PENDING);
        assertThat(task.getRetryCount()).isZero();

        assertThat(redisTemplate.opsForValue().get("ticket:stock:" + TEST_SKU_ID)).isEqualTo("10");
        assertThat(redisTemplate.opsForSet()
                .isMember("ticket:order:user:" + TEST_SKU_ID, String.valueOf(TEST_USER_ID))).isTrue();
    }

    @Test
    void retryRollbackTasks_marksTaskSuccess_whenRedisRollbackSucceeds() {
        TicketRollbackTask task = TicketRollbackTask.builder()
                .orderId(testOrderId)
                .userId(TEST_USER_ID)
                .skuId(TEST_SKU_ID)
                .status(TicketRollbackTask.STATUS_PENDING)
                .retryCount(0)
                .errorMessage("previous failure")
                .build();
        ticketRollbackTaskMapper.insert(task);

        paymentService.retryRollbackTasks();

        TicketRollbackTask updated = ticketRollbackTaskMapper.selectById(task.getId());
        assertThat(updated.getStatus()).isEqualTo(TicketRollbackTask.STATUS_SUCCESS);
        assertThat(updated.getErrorMessage()).isNull();
        assertThat(redisTemplate.opsForValue().get("ticket:stock:" + TEST_SKU_ID)).isEqualTo("11");
        assertThat(redisTemplate.opsForSet()
                .isMember("ticket:order:user:" + TEST_SKU_ID, String.valueOf(TEST_USER_ID))).isFalse();
    }

    @TestConfiguration
    static class PaymentServiceTestConfig {

        @Bean
        @Primary
        FakeRedisRollbackService fakeRedisRollbackService(StringRedisTemplate redisTemplate) {
            return new FakeRedisRollbackService(redisTemplate);
        }
    }

    static class FakeRedisRollbackService implements RedisRollbackService {

        private final StringRedisTemplate redisTemplate;
        private boolean failNextRollback;

        FakeRedisRollbackService(StringRedisTemplate redisTemplate) {
            this.redisTemplate = redisTemplate;
        }

        @Override
        public void rollback(Long skuId, Long userId) {
            if (failNextRollback) {
                failNextRollback = false;
                throw new IllegalStateException("rollback failed");
            }
            redisTemplate.opsForValue().increment("ticket:stock:" + skuId);
            redisTemplate.opsForSet().remove("ticket:order:user:" + skuId, String.valueOf(userId));
        }
    }
}
