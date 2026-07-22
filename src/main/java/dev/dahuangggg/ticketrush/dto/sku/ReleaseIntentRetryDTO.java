package dev.dahuangggg.ticketrush.dto.sku;

/** 管理端手工重驱失败 Release Intent 的结果。 */
public record ReleaseIntentRetryDTO(boolean requeued) {
}
