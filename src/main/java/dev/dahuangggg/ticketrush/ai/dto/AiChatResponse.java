package dev.dahuangggg.ticketrush.ai.dto;

/** 稳定的非流式 AI 响应，不直接暴露模型 SDK 类型。 */
public record AiChatResponse(String sessionId, String answer) {
}
