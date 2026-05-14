package dev.dahuangggg.ticketrush.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import dev.dahuangggg.ticketrush.entity.TicketOrder;
import dev.dahuangggg.ticketrush.entity.TicketOrderMsg;
import dev.dahuangggg.ticketrush.infrastructure.mq.TicketRushMessage;
import dev.dahuangggg.ticketrush.mapper.TicketOrderMapper;
import dev.dahuangggg.ticketrush.mapper.TicketOrderMsgMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class OrderServiceTest {

    @Autowired
    private OrderService orderService;

    @Autowired
    private TicketOrderMapper ticketOrderMapper;

    @Autowired
    private TicketOrderMsgMapper ticketOrderMsgMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // 测试专用 userId（不与种子数据冲突），sku 3001 price=38000，event 2001
    private static final Long TEST_USER_ID  = 99001L;
    private static final Long TEST_SKU_ID   = 3001L;
    private static final Long TEST_EVENT_ID = 2001L;

    private String testMessageId;

    @BeforeEach
    void setup() {
        testMessageId = UUID.randomUUID().toString();
        // 物理删除（绕过软删除），保证唯一索引干净，避免测试间干扰
        jdbcTemplate.update("DELETE FROM tb_ticket_order WHERE user_id = ?", TEST_USER_ID);
        jdbcTemplate.update("DELETE FROM tb_ticket_order_msg WHERE user_id = ?", TEST_USER_ID);
    }

    @AfterEach
    void cleanup() {
        jdbcTemplate.update("DELETE FROM tb_ticket_order WHERE user_id = ?", TEST_USER_ID);
        jdbcTemplate.update("DELETE FROM tb_ticket_order_msg WHERE user_id = ?", TEST_USER_ID);
    }

    @Test
    void createOrder_insertsOrderAndMsg_onSuccess() {
        TicketRushMessage msg = new TicketRushMessage(
                testMessageId, TEST_USER_ID, TEST_EVENT_ID, TEST_SKU_ID, 1);

        orderService.createOrder(msg);

        // 验证订单创建，状态为待支付，总金额 = 38000 * 1
        TicketOrder order = ticketOrderMapper.selectOne(
                new LambdaQueryWrapper<TicketOrder>()
                        .eq(TicketOrder::getUserId, TEST_USER_ID)
                        .eq(TicketOrder::getSkuId, TEST_SKU_ID));
        assertThat(order).isNotNull();
        assertThat(order.getStatus()).isEqualTo(0);
        assertThat(order.getTotalAmount()).isEqualTo(38000L);
        assertThat(order.getOrderNo()).isNotBlank();

        // 验证消息追踪记录状态为成功
        TicketOrderMsg orderMsg = ticketOrderMsgMapper.selectOne(
                new LambdaQueryWrapper<TicketOrderMsg>()
                        .eq(TicketOrderMsg::getMessageId, testMessageId));
        assertThat(orderMsg).isNotNull();
        assertThat(orderMsg.getStatus()).isEqualTo(1);
    }

    @Test
    void createOrder_isIdempotent_whenDuplicateMessageId() {
        TicketRushMessage msg = new TicketRushMessage(
                testMessageId, TEST_USER_ID, TEST_EVENT_ID, TEST_SKU_ID, 1);

        orderService.createOrder(msg);   // 第一次：正常创单
        orderService.createOrder(msg);   // 第二次：messageId 重复，幂等跳过

        // 只有一条订单记录
        Long orderCount = ticketOrderMapper.selectCount(
                new LambdaQueryWrapper<TicketOrder>()
                        .eq(TicketOrder::getUserId, TEST_USER_ID)
                        .eq(TicketOrder::getSkuId, TEST_SKU_ID));
        assertThat(orderCount).isEqualTo(1);

        // 消息追踪记录仍应只有一条，且状态为成功
        Long msgCount = ticketOrderMsgMapper.selectCount(
                new LambdaQueryWrapper<TicketOrderMsg>()
                        .eq(TicketOrderMsg::getMessageId, testMessageId)
                        .eq(TicketOrderMsg::getStatus, 1));
        assertThat(msgCount).isEqualTo(1);
    }
}
