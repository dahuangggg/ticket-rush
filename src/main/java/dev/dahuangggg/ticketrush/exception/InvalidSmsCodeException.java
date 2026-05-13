package dev.dahuangggg.ticketrush.exception;

public class InvalidSmsCodeException extends RuntimeException {

    public InvalidSmsCodeException() {
        super("验证码错误或已过期");
    }
}
