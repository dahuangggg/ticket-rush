package dev.dahuangggg.ticketrush.exception;

/**
 * 同一验证码连续输错次数达到上限。
 *
 * <p>达到上限时验证码已经在 Redis Lua 脚本中失效，用户必须重新获取验证码。
 * 单独的异常类型让 HTTP 层能够返回 429，而不是把安全限流伪装成普通参数错误。</p>
 */
public class SmsCodeAttemptsExceededException extends RuntimeException {

    public SmsCodeAttemptsExceededException() {
        super("验证码错误次数过多，请重新获取验证码");
    }
}
