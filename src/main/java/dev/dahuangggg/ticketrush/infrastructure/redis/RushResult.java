package dev.dahuangggg.ticketrush.infrastructure.redis;

/**
 * Redis Lua 抢票脚本的返回值枚举，与 ticket_rush.lua 的返回码一一对应。
 * 修改 Lua 脚本返回码时必须同步更新此枚举。
 */
public enum RushResult {

    SUCCESS(0L),
    SOLD_OUT(1L),
    DUPLICATE(2L);

    private final long code;

    RushResult(long code) {
        this.code = code;
    }

    public long code() {
        return code;
    }

    public static RushResult fromCode(Long code) {
        if (code == null) {
            throw new IllegalStateException("Redis Lua 脚本返回 null（可能是连接异常）");
        }
        for (RushResult r : values()) {
            if (r.code == code) {
                return r;
            }
        }
        throw new IllegalStateException("未知的 Lua 返回码: " + code);
    }
}
