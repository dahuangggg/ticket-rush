package dev.dahuangggg.ticketrush.dto.reminder;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReminderDTO {
    private Long id;
    private Long skuId;
    private Long eventId;
    private Integer leadMinutes;
    private LocalDateTime triggerAt;
    private String status;
    private Boolean unread;
    private LocalDateTime createTime;
}
