package dev.dahuangggg.ticketrush.exception;

/**
 * AI 模型或其上游服务暂时不可用。
 *
 * <p>Controller 不把底层异常类名暴露给客户端；统一异常处理器会返回稳定的
 * {@code SERVICE_UNAVAILABLE} 错误契约，同时在服务端日志中保留原始原因。</p>
 */
public class AiServiceUnavailableException extends RuntimeException {

    public AiServiceUnavailableException(Throwable cause) {
        super("AI 服务暂时不可用", cause);
    }
}
