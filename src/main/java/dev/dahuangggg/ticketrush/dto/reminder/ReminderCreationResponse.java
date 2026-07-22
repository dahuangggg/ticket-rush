package dev.dahuangggg.ticketrush.dto.reminder;

import java.time.LocalDateTime;

/** 普通业务 API 的提醒创建结果；不属于 AI Tool 协议。 */
public record ReminderCreationResponse(
        boolean ok,
        Long reminderId,
        LocalDateTime triggerAt,
        String message
) {
    public static ReminderCreationResponse success(
            Long reminderId, LocalDateTime triggerAt, String message) {
        return new ReminderCreationResponse(true, reminderId, triggerAt, message);
    }

    public static ReminderCreationResponse failure(String message) {
        return new ReminderCreationResponse(false, null, null, message);
    }
}
