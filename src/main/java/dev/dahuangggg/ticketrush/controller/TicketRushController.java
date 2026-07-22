package dev.dahuangggg.ticketrush.controller;

import dev.dahuangggg.ticketrush.dto.rush.TicketRushRequest;
import dev.dahuangggg.ticketrush.dto.rush.TicketRushResponse;
import dev.dahuangggg.ticketrush.security.UserContext;
import dev.dahuangggg.ticketrush.service.TicketRushService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/ticket-rush")
public class TicketRushController {

    private final TicketRushService ticketRushService;

    public TicketRushController(TicketRushService ticketRushService) {
        this.ticketRushService = ticketRushService;
    }

    /**
     * 提交抢票请求。
     *
     * JWT 拦截器已保证此处 UserContext.getUserId() 非 null。
     * Lua 脚本原子校验库存和去重，并把 Reservation 写入 Redis Outbox。
     * Idempotency-Key 应由客户端针对一次用户动作生成，重试时保持不变。
     */
    @PostMapping("/requests")
    public ResponseEntity<TicketRushResponse> rush(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody TicketRushRequest request) {
        Long userId = UserContext.getUserId();
        return ResponseEntity.accepted()
                .body(ticketRushService.rush(userId, request, idempotencyKey));
    }

    @GetMapping("/reservations/{reservationId}")
    public TicketRushResponse getReservation(@PathVariable String reservationId) {
        return ticketRushService.getReservation(reservationId, UserContext.getUserId());
    }
}
