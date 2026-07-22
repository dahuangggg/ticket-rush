package dev.dahuangggg.ticketrush.infrastructure.mq;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.dahuangggg.ticketrush.domain.order.OrderCreationResult;
import dev.dahuangggg.ticketrush.service.OrderService;
import dev.dahuangggg.ticketrush.service.RushReservationStore;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** Kafka 到 Order Intake Module 的薄 Adapter。 */
@Component
public class TicketRushConsumer {

    private static final Logger log = LoggerFactory.getLogger(TicketRushConsumer.class);

    private final OrderService orderService;
    private final RushReservationStore reservationStore;
    private final ObjectMapper objectMapper;

    public TicketRushConsumer(OrderService orderService,
                              RushReservationStore reservationStore,
                              ObjectMapper objectMapper) {
        this.orderService = orderService;
        this.reservationStore = reservationStore;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = TicketRushProducer.TOPIC, groupId = "${spring.kafka.consumer.group-id}")
    public void consume(ConsumerRecord<String, String> record) {
        TicketRushMessage message;
        try {
            message = objectMapper.readValue(record.value(), TicketRushMessage.class);
        } catch (JsonProcessingException e) {
            // 不推进为“成功”；交给统一重试与 DLT Recovery Adapter。
            throw new IllegalArgumentException("Invalid ticket rush message", e);
        }

        OrderCreationResult result = orderService.createOrder(message);
        if (result.status() == OrderCreationResult.Status.CREATED
                || result.status() == OrderCreationResult.Status.ALREADY_CREATED) {
            try {
                reservationStore.markOrderCreated(message.reservationId(), result.orderId());
            } catch (RuntimeException e) {
                // MySQL Ledger 已是权威状态。Redis 状态只是查询加速，失败不能把已成功创单送入 DLT。
                log.warn("Order committed but Redis reservation status update failed: reservationId={}",
                        message.reservationId(), e);
            }
            return;
        }
        try {
            reservationStore.markRejected(message.reservationId(), result.reason());
        } catch (RuntimeException e) {
            // Release Intent 已在 Order Intake 事务中提交，worker 仍会完成幂等释放。
            log.warn("Rejected reservation Redis status update failed: reservationId={}",
                    message.reservationId(), e);
        }
        log.warn("Reservation rejected by Order Intake: reservationId={} reason={}",
                message.reservationId(), result.reason());
    }
}
