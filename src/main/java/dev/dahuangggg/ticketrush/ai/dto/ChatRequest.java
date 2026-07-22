package dev.dahuangggg.ticketrush.ai.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * AI 对话请求。
 *
 * <p>限制 sessionId 字符集可以避免用户把 Redis key namespace 变成任意文本；限制消息
 * 长度则同时保护模型调用成本、上下文窗口和服务线程。DTO 使用 record，保证 API 契约
 * 不会因为 Lombok getter/setter 或可变字段而漂移。</p>
 */
public record ChatRequest(
        @NotBlank(message = "会话 ID 不能为空")
        @Size(max = 64, message = "会话 ID 最长为 64 个字符")
        @Pattern(regexp = "[A-Za-z0-9._-]+", message = "会话 ID 只能包含字母、数字、点、下划线和连字符")
        String sessionId,

        @NotBlank(message = "消息不能为空")
        @Size(max = 2_000, message = "消息最长为 2000 个字符")
        String message
) {
}
