package dev.dahuangggg.ticketrush.controller;

import dev.dahuangggg.ticketrush.dto.order.OrderDTO;
import dev.dahuangggg.ticketrush.security.UserContext;
import dev.dahuangggg.ticketrush.service.OrderService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    /** 按订单 ID 查询，仅返回当前登录用户自己的订单。 */
    @GetMapping("/{orderId}")
    public OrderDTO getById(@PathVariable Long orderId) {
        Long userId = UserContext.getUserId();
        return orderService.getById(orderId, userId);
    }

    /** 按业务订单号查询，仅返回当前登录用户自己的订单。 */
    @GetMapping("/by-no/{orderNo}")
    public OrderDTO getByOrderNo(@PathVariable String orderNo) {
        Long userId = UserContext.getUserId();
        return orderService.getByOrderNo(orderNo, userId);
    }

    /** 查询当前登录用户的所有订单，按创建时间倒序。 */
    @GetMapping("/me")
    public List<OrderDTO> listMyOrders() {
        Long userId = UserContext.getUserId();
        return orderService.listByUser(userId);
    }
}
