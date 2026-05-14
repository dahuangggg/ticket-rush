package dev.dahuangggg.ticketrush.controller;

import dev.dahuangggg.ticketrush.security.UserContext;
import dev.dahuangggg.ticketrush.service.PaymentService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/**
 * 支付与取消控制器，处理订单状态变更请求。
 *
 * userId 从 UserContext 读取（JWT 拦截器写入），不信任请求参数。
 */
@RestController
@RequestMapping("/api/orders")
public class PaymentController {

    private final PaymentService paymentService;

    public PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    /**
     * 模拟支付，将订单状态从待支付（0）变为已支付（1）。
     */
    @PostMapping("/{orderId}/pay")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void pay(@PathVariable Long orderId) {
        paymentService.pay(orderId, UserContext.getUserId());
    }

    /**
     * 用户主动取消订单，将状态从待支付（0）变为已取消（2），并回滚 Redis 库存。
     */
    @PostMapping("/{orderId}/cancel")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancel(@PathVariable Long orderId) {
        paymentService.cancel(orderId, UserContext.getUserId());
    }
}
