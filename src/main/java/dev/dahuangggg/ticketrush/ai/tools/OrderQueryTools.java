package dev.dahuangggg.ticketrush.ai.tools;

import dev.dahuangggg.ticketrush.dto.order.OrderDTO;
import dev.dahuangggg.ticketrush.ai.dto.OrderQueryResult;
import dev.dahuangggg.ticketrush.entity.TicketOrder;
import dev.dahuangggg.ticketrush.security.UserContext;
import dev.dahuangggg.ticketrush.service.OrderService;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

@Component
public class OrderQueryTools {

    private final OrderService orderService;

    public OrderQueryTools(OrderService orderService) {
        this.orderService = orderService;
    }

    @Tool("只读查询当前用户的订单。可选 status：PENDING_PAY / PAID / CANCELED / TIMEOUT；返回 ok/error/orders。")
    public OrderQueryResult getMyOrders(@P("订单状态，可选") String status) {
        Long userId = UserContext.getUserId();
        Objects.requireNonNull(userId, "missing user context");
        Integer code = parseStatus(status);
        if (Integer.valueOf(-1).equals(code)) {
            return OrderQueryResult.invalid(
                    "不支持的订单状态；可选值：PENDING_PAY、PAID、CANCELED、TIMEOUT");
        }

        List<OrderDTO> all = orderService.listByUser(userId);
        if (code == null) return OrderQueryResult.success(all);
        final int target = code;
        return OrderQueryResult.success(
                all.stream().filter(o -> o.status() != null && o.status() == target).toList());
    }

    private static Integer parseStatus(String status) {
        if (status == null || status.isBlank()) return null;
        return switch (status.trim().toUpperCase(Locale.ROOT)) {
            case "PENDING_PAY" -> TicketOrder.STATUS_PENDING;
            case "PAID" -> TicketOrder.STATUS_PAID;
            case "CANCELED", "CANCELLED" -> TicketOrder.STATUS_CANCELED;
            case "TIMEOUT" -> TicketOrder.STATUS_TIMEOUT;
            default -> -1;
        };
    }
}
