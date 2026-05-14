package dev.dahuangggg.ticketrush.dto.order;

import java.time.LocalDateTime;

/**
 * 订单响应 DTO，用于接口返回，不暴露内部字段（如 deleted）。
 */
public record OrderDTO(
        Long id,
        String orderNo,
        Long eventId,
        Long skuId,
        Integer quantity,
        Long totalAmount,
        Integer status,
        LocalDateTime createTime,
        LocalDateTime payTime,
        LocalDateTime cancelTime
) {}
