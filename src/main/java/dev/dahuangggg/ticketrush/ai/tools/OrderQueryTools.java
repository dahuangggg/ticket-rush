package dev.dahuangggg.ticketrush.ai.tools;

import dev.dahuangggg.ticketrush.dto.order.OrderDTO;
import dev.dahuangggg.ticketrush.security.UserContext;
import dev.dahuangggg.ticketrush.service.OrderService;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

@Component
public class OrderQueryTools {

    private final OrderService orderService;

    public OrderQueryTools(OrderService orderService) {
        this.orderService = orderService;
    }

    @Tool("查询当前用户的所有订单。可选 status 过滤：PENDING_PAY / PAID / CANCELLED / TIMEOUT")
    public List<OrderDTO> getMyOrders(@P("订单状态，可选") String status) {
        Long userId = UserContext.getUserId();
        Objects.requireNonNull(userId, "missing user context");
        List<OrderDTO> all = orderService.listByUser(userId);
        if (status == null || status.isBlank()) return all;
        int code = switch (status.toUpperCase()) {
            case "PENDING_PAY" -> 0;
            case "PAID"        -> 1;
            case "CANCELLED"   -> 2;
            case "TIMEOUT"     -> 3;
            default -> -1;
        };
        if (code < 0) return all;
        final int target = code;
        return all.stream().filter(o -> o.status() != null && o.status() == target).toList();
    }
}
