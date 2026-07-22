package dev.dahuangggg.ticketrush.domain.rush;

/** Redis 幂等释放的结果。 */
public enum ReleaseResult {
    RELEASED,
    ALREADY_RELEASED,
    RESERVATION_NOT_FOUND
}
