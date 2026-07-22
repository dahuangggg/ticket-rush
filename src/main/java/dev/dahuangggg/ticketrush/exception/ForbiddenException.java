package dev.dahuangggg.ticketrush.exception;

/**
 * 用户已经通过身份认证，但没有执行当前操作所需的权限。
 *
 * <p>它与 {@link UnauthorizedException} 的区别是：401 表示“尚未证明你是谁”，
 * 403 表示“已经知道你是谁，但你不能做这件事”。</p>
 */
public class ForbiddenException extends RuntimeException {

    public ForbiddenException(String message) {
        super(message);
    }
}
