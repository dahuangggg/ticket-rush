package dev.dahuangggg.ticketrush.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import dev.dahuangggg.ticketrush.entity.TicketOrder;
import dev.dahuangggg.ticketrush.exception.OrderNotFoundException;
import dev.dahuangggg.ticketrush.exception.OrderNotPendingException;
import dev.dahuangggg.ticketrush.mapper.TicketOrderMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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
    private StringRedisTemplate redisTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // 测试专用 userId，与其他测试用例不冲突
    private static final Long TEST_USER_ID  = 99002L;
    private static final Long TEST_SKU_ID   = 3001L;
    private static final Long TEST_EVENT_ID = 2001L;

    private Long testOrderId;

    @BeforeEach
    void setup() {
        // 物理删除旧数据，避免唯一索引冲突
        jdbcTemplate.update("DELETE FROM tb_ticket_order WHERE user_id = ?", TEST_USER_ID);

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
                .status(0)
                .build();
        ticketOrderMapper.insert(order);
        testOrderId = order.getId();
    }

    @AfterEach
    void cleanup() {
        jdbcTemplate.update("DELETE FROM tb_ticket_order WHERE user_id = ?", TEST_USER_ID);
        redisTemplate.delete("ticket:stock:" + TEST_SKU_ID);
        redisTemplate.opsForSet().remove("ticket:order:user:" + TEST_SKU_ID, String.valueOf(TEST_USER_ID));
    }

    @Test
    void pay_changesStatusTo1() {
        paymentService.pay(testOrderId, TEST_USER_ID);

        TicketOrder order = ticketOrderMapper.selectById(testOrderId);
        assertThat(order.getStatus()).isEqualTo(1);
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
        assertThat(order.getStatus()).isEqualTo(2);
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
        assertThat(order.getStatus()).isEqualTo(3);
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
        assertThat(order.getStatus()).isEqualTo(0);
    }
}
