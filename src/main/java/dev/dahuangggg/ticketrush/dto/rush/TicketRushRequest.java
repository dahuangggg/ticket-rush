package dev.dahuangggg.ticketrush.dto.rush;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * 抢票请求体。
 *
 * quantity 限制为 1：当前所有票档的 limitPerUser=1，不支持一次多张。
 */
public record TicketRushRequest(
        @NotNull Long eventId,
        @NotNull Long skuId,
        @NotNull @Min(1) @Max(1) Integer quantity
) {}
