package dev.dahuangggg.ticketrush.controller;

import dev.dahuangggg.ticketrush.dto.rush.TicketRushRequest;
import dev.dahuangggg.ticketrush.dto.rush.TicketRushResponse;
import dev.dahuangggg.ticketrush.security.UserContext;
import dev.dahuangggg.ticketrush.service.TicketRushService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
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
     * Lua 脚本原子校验库存和去重，成功后立即返回 QUEUED，订单由 Kafka 消费者异步创建。
     */
    @PostMapping("/requests")
    public TicketRushResponse rush(@Valid @RequestBody TicketRushRequest request) {
        Long userId = UserContext.getUserId();
        return ticketRushService.rush(userId, request);
    }
}
