package dev.dahuangggg.ticketrush.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import dev.dahuangggg.ticketrush.domain.order.OrderCreationResult;
import dev.dahuangggg.ticketrush.domain.rush.ReservationStatus;
import dev.dahuangggg.ticketrush.entity.InventoryReleaseIntent;
import dev.dahuangggg.ticketrush.entity.RushReservation;
import dev.dahuangggg.ticketrush.entity.TicketOrder;
import dev.dahuangggg.ticketrush.entity.TicketOrderMsg;
import dev.dahuangggg.ticketrush.infrastructure.mq.TicketRushMessage;
import dev.dahuangggg.ticketrush.mapper.InventoryReleaseIntentMapper;
import dev.dahuangggg.ticketrush.mapper.RushReservationMapper;
import dev.dahuangggg.ticketrush.mapper.TicketOrderMapper;
import dev.dahuangggg.ticketrush.mapper.TicketOrderMsgMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 需要真实 MySQL 的 Order Intake 事务与唯一约束集成测试。 */
@Tag("integration")
@SpringBootTest
class OrderServiceTest {

    private static final Long USER_ID = 99001L;
    private static final Long SECOND_USER_ID = 99003L;
    private static final Long SKU_ID = 3001L;
    private static final Long EVENT_ID = 2001L;

    @Autowired private OrderService orderService;
    @Autowired private TicketOrderMapper orderMapper;
    @Autowired private TicketOrderMsgMapper messageMapper;
    @Autowired private RushReservationMapper reservationMapper;
    @Autowired private InventoryReleaseIntentMapper intentMapper;
    @Autowired private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        clean();
    }

    @AfterEach
    void tearDown() {
        clean();
    }

    @Test
    void createOrder_commitsInboxOrderAndLedgerTogether() {
        TicketRushMessage message = message(USER_ID);
        insertLedger(message);

        OrderCreationResult result = orderService.createOrder(message);

        assertThat(result.status()).isEqualTo(OrderCreationResult.Status.CREATED);
        TicketOrder order = orderMapper.selectById(result.orderId());
        assertThat(order.getReservationId()).isEqualTo(message.reservationId());
        assertThat(order.getTotalAmount()).isEqualTo(38000L);

        TicketOrderMsg inbox = findInbox(message.messageId());
        assertThat(inbox.getStatus()).isEqualTo(TicketOrderMsg.STATUS_SUCCESS);
        assertThat(inbox.getOrderId()).isEqualTo(order.getId());
        assertThat(findLedger(message.reservationId()).getStatus())
                .isEqualTo(ReservationStatus.ORDER_CREATED.code());
    }

    @Test
    void duplicateMessage_returnsSameOrderWithoutCreatingAnother() {
        TicketRushMessage message = message(USER_ID);
        insertLedger(message);

        OrderCreationResult first = orderService.createOrder(message);
        OrderCreationResult second = orderService.createOrder(message);

        assertThat(second.status()).isEqualTo(OrderCreationResult.Status.ALREADY_CREATED);
        assertThat(second.orderId()).isEqualTo(first.orderId());
        assertThat(orderMapper.selectCount(new LambdaQueryWrapper<TicketOrder>()
                .eq(TicketOrder::getReservationId, message.reservationId()))).isOne();
    }

    @Test
    void secondReservationForActiveUser_isRejectedAndGetsReleaseIntent() {
        TicketRushMessage first = message(USER_ID);
        insertLedger(first);
        orderService.createOrder(first);

        TicketRushMessage second = message(USER_ID);
        insertLedger(second);
        OrderCreationResult result = orderService.createOrder(second);

        assertThat(result.status()).isEqualTo(OrderCreationResult.Status.REJECTED);
        assertThat(intentMapper.selectCount(new LambdaQueryWrapper<InventoryReleaseIntent>()
                .eq(InventoryReleaseIntent::getReservationId, second.reservationId()))).isOne();
        assertThat(orderMapper.selectCount(new LambdaQueryWrapper<TicketOrder>()
                .eq(TicketOrder::getUserId, USER_ID))).isOne();
    }

    @Test
    void canceledOrder_doesNotBlockNewReservation() {
        String oldReservationId = reservationId();
        TicketOrder canceled = TicketOrder.builder()
                .orderNo(UUID.randomUUID().toString().replace("-", ""))
                .reservationId(oldReservationId)
                .userId(SECOND_USER_ID)
                .eventId(EVENT_ID)
                .skuId(SKU_ID)
                .quantity(1)
                .totalAmount(38000L)
                .status(TicketOrder.STATUS_CANCELED)
                .cancelTime(LocalDateTime.now())
                .build();
        orderMapper.insert(canceled);

        TicketRushMessage message = message(SECOND_USER_ID);
        insertLedger(message);
        OrderCreationResult result = orderService.createOrder(message);

        assertThat(result.status()).isEqualTo(OrderCreationResult.Status.CREATED);
        assertThat(orderMapper.selectCount(new LambdaQueryWrapper<TicketOrder>()
                .eq(TicketOrder::getUserId, SECOND_USER_ID))).isEqualTo(2);
    }

    @Test
    void mismatchedPayloadRollsBackInboxSoCorrectStableMessageCanRetry() {
        TicketRushMessage correct = message(SECOND_USER_ID);
        insertLedger(correct);
        TicketRushMessage forged = new TicketRushMessage(
                correct.messageId(), correct.reservationId(), correct.userId(), correct.eventId(),
                correct.skuId(), correct.quantity(), correct.unitPrice() + 1);

        assertThatThrownBy(() -> orderService.createOrder(forged))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(findInbox(correct.messageId())).isNull();

        OrderCreationResult retried = orderService.createOrder(correct);
        assertThat(retried.status()).isEqualTo(OrderCreationResult.Status.CREATED);
    }

    @Test
    void mismatchedMessageAndReservationIdentityCannotPoisonAnotherInbox() {
        TicketRushMessage legitimate = message(SECOND_USER_ID);
        insertLedger(legitimate);
        TicketRushMessage forged = new TicketRushMessage(
                "3001-someone-elses-message",
                legitimate.reservationId(),
                legitimate.userId(),
                legitimate.eventId(),
                legitimate.skuId(),
                legitimate.quantity(),
                legitimate.unitPrice());

        assertThatThrownBy(() -> orderService.createOrder(forged))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(findInbox(forged.messageId())).isNull();
        assertThat(findInbox(legitimate.messageId())).isNull();

        assertThat(orderService.createOrder(legitimate).status())
                .isEqualTo(OrderCreationResult.Status.CREATED);
    }

    private TicketRushMessage message(Long userId) {
        String reservationId = reservationId();
        return new TicketRushMessage(
                reservationId, reservationId, userId, EVENT_ID, SKU_ID, 1, 38000L);
    }

    private String reservationId() {
        return SKU_ID + "-" + UUID.randomUUID().toString().replace("-", "");
    }

    private void insertLedger(TicketRushMessage message) {
        reservationMapper.insert(RushReservation.builder()
                .reservationId(message.reservationId())
                .userId(message.userId())
                .eventId(message.eventId())
                .skuId(message.skuId())
                .quantity(message.quantity())
                .unitPrice(message.unitPrice())
                .status(ReservationStatus.QUEUED.code())
                .build());
    }

    private RushReservation findLedger(String reservationId) {
        return reservationMapper.selectOne(new LambdaQueryWrapper<RushReservation>()
                .eq(RushReservation::getReservationId, reservationId));
    }

    private TicketOrderMsg findInbox(String messageId) {
        return messageMapper.selectOne(new LambdaQueryWrapper<TicketOrderMsg>()
                .eq(TicketOrderMsg::getMessageId, messageId));
    }

    private void clean() {
        jdbcTemplate.update("DELETE FROM tb_inventory_release_intent WHERE user_id IN (?, ?)", USER_ID, SECOND_USER_ID);
        jdbcTemplate.update("DELETE FROM tb_ticket_order_msg WHERE user_id IN (?, ?)", USER_ID, SECOND_USER_ID);
        jdbcTemplate.update("DELETE FROM tb_ticket_order WHERE user_id IN (?, ?)", USER_ID, SECOND_USER_ID);
        jdbcTemplate.update("DELETE FROM tb_rush_reservation WHERE user_id IN (?, ?)", USER_ID, SECOND_USER_ID);
    }
}
