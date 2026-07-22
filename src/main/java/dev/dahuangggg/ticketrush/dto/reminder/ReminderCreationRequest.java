package dev.dahuangggg.ticketrush.dto.reminder;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record ReminderCreationRequest(
        @NotNull Long skuId,
        @Min(1) @Max(1440) Integer leadMinutes
) {
}
