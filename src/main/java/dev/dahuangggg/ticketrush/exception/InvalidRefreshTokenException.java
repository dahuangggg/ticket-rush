package dev.dahuangggg.ticketrush.exception;

/**
 * refreshToken 无效异常。
 *
 * 以下情况会抛出此异常：
 * 1. 客户端传来的 refreshToken 在 Redis 中不存在（从未签发过）。
 * 2. refreshToken 已超过有效期，Redis key 自动过期被删除。
 * 3. 用户已主动退出登录，refreshToken 被手动从 Redis 中删除。
 *
 * 统一由 GlobalExceptionHandler 转成 401 响应，
 * 前端收到 401 后应跳转登录页并清空本地存储的 token。
 */
public class InvalidRefreshTokenException extends RuntimeException {

    public InvalidRefreshTokenException() {
        super("登录已过期，请重新登录");
    }
}
