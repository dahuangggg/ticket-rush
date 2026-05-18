package dev.dahuangggg.ticketrush.dto.reminder;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** Function-Calling 工具返回结构。LLM 必须如实复述。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReminderToolResult {
    private boolean ok;
    private Long reminderId;
    private LocalDateTime triggerAt;
    private String message;
}
