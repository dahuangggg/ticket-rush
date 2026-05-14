package dev.dahuangggg.ticketrush.exception;

import dev.dahuangggg.ticketrush.dto.common.ErrorResponse;
import dev.dahuangggg.ticketrush.exception.TicketSkuNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * 处理短信验证码错误。
     *
     * 只要验证码不存在、过期或输入错误，业务层都会抛出 InvalidSmsCodeException。
     * 这里统一转成 400 响应，前端可以根据 code=INVALID_SMS_CODE 展示固定错误提示。
     */
    @ExceptionHandler(SmsCooldownException.class)
    @ResponseStatus(HttpStatus.TOO_MANY_REQUESTS)
    public ErrorResponse handleSmsCooldown(SmsCooldownException exception) {
        return new ErrorResponse("SMS_COOLDOWN", exception.getMessage());
    }

    @ExceptionHandler(InvalidRefreshTokenException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    public ErrorResponse handleInvalidRefreshToken(InvalidRefreshTokenException exception) {
        return new ErrorResponse("UNAUTHORIZED", exception.getMessage());
    }

    @ExceptionHandler(InvalidSmsCodeException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ErrorResponse handleInvalidSmsCode(InvalidSmsCodeException exception) {
        return new ErrorResponse("INVALID_SMS_CODE", exception.getMessage());
    }

    /**
     * 处理未登录或 JWT 无效。
     *
     * 这类错误返回 401，而不是 400。
     * 前端看到 401 时，通常应该跳转登录页或清空本地 token。
     */
    @ExceptionHandler(UnauthorizedException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    public ErrorResponse handleUnauthorized(UnauthorizedException exception) {
        return new ErrorResponse("UNAUTHORIZED", exception.getMessage());
    }

    /**
     * 处理请求参数校验错误。
     *
     * 例如手机号为空、手机号格式不对、验证码不是 6 位数字，
     * 都会被 @Valid 触发并进入这里。
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ErrorResponse handleValidationError(MethodArgumentNotValidException exception) {
        String message = exception.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(error -> error.getDefaultMessage() == null ? "请求参数错误" : error.getDefaultMessage())
                .orElse("请求参数错误");
        return new ErrorResponse("BAD_REQUEST", message);
    }

    @ExceptionHandler(EventNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ErrorResponse handleEventNotFound(EventNotFoundException exception) {
        return new ErrorResponse("EVENT_NOT_FOUND", exception.getMessage());
    }

    @ExceptionHandler(TicketSkuNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ErrorResponse handleTicketSkuNotFound(TicketSkuNotFoundException exception) {
        return new ErrorResponse("TICKET_SKU_NOT_FOUND", exception.getMessage());
    }

    /**
     * 未命中错误处理。
     */
    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ErrorResponse handleUnexpected(Exception exception) {
        log.error("Unexpected error", exception);
        return new ErrorResponse("INTERNAL_ERROR", "服务器内部错误");
    }
}
