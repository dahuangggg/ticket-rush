package dev.dahuangggg.ticketrush.ai.dto;

import dev.dahuangggg.ticketrush.dto.order.OrderDTO;

import java.util.List;

/** 订单查询工具的稳定结果；非法状态不会退化成“返回所有订单”。 */
public record OrderQueryResult(boolean ok, String error, List<OrderDTO> orders) {

    public static OrderQueryResult success(List<OrderDTO> orders) {
        return new OrderQueryResult(true, null, List.copyOf(orders));
    }

    public static OrderQueryResult invalid(String error) {
        return new OrderQueryResult(false, error, List.of());
    }
}
