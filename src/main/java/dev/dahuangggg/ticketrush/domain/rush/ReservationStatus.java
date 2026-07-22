package dev.dahuangggg.ticketrush.domain.rush;

import java.util.Arrays;

/**
 * 抢票预占的生命周期。
 *
 * <p>状态同时写入 Redis 快路径和 MySQL Reservation Ledger。Redis 用于低延迟查询与
 * 幂等释放，MySQL 用于订单校验、恢复和对账。</p>
 */
public enum ReservationStatus {

    RESERVED(0),
    QUEUED(1),
    ORDER_CREATED(2),
    PAID(3),
    RELEASE_PENDING(4),
    RELEASED(5),
    REJECTED(6);

    private final int code;

    ReservationStatus(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }

    public static ReservationStatus fromCode(Integer code) {
        if (code == null) {
            throw new IllegalArgumentException("Reservation status must not be null");
        }
        return Arrays.stream(values())
                .filter(status -> status.code == code)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown reservation status: " + code));
    }
}
