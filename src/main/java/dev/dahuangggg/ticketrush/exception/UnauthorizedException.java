package dev.dahuangggg.ticketrush.exception;

/**
 * 未登录或登录态无效异常。
 *
 * 只要请求没有携带 Bearer token、token 格式错误、签名错误或 token 过期，
 * 都统一抛出这个异常，并由 GlobalExceptionHandler 转成 401 响应。
 */
public class UnauthorizedException extends RuntimeException {

    public UnauthorizedException(String message) {
        super(message);
    }
}
