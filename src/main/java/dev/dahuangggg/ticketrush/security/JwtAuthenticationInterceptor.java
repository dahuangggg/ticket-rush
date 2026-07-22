package dev.dahuangggg.ticketrush.security;

import dev.dahuangggg.ticketrush.exception.UnauthorizedException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.AsyncHandlerInterceptor;

@Component
public class JwtAuthenticationInterceptor implements AsyncHandlerInterceptor {

    private static final String AUTHORIZATION = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtTokenService jwtTokenService;

    public JwtAuthenticationInterceptor(JwtTokenService jwtTokenService) {
        this.jwtTokenService = jwtTokenService;
    }

    /**
     * 在请求进入 Controller 前校验登录态。
     *
     * JWT 模式下服务端不保存 Session，所以每个受保护请求都必须携带：
     * Authorization: Bearer <accessToken>
     *
     * 校验通过后，把 token 中的 userId/phone 放入 UserContext，
     * 后续 Controller 和 Service 就可以知道当前请求属于哪个用户。
     */
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // 防御性清理：如果上一个异步请求错误地遗留了 ThreadLocal，绝不能让它参与
        // 当前请求的鉴权。当前 token 解析失败时，也不会残留旧用户。
        UserContext.clear();
        String authorization = request.getHeader(AUTHORIZATION);
        if (authorization == null || !authorization.startsWith(BEARER_PREFIX)) {
            throw new UnauthorizedException("请先登录");
        }

        String token = authorization.substring(BEARER_PREFIX.length());
        UserContext.set(jwtTokenService.parseAccessToken(token));
        return true;
    }

    /**
     * 请求结束后清理 ThreadLocal。
     *
     * Web 容器会复用线程，如果不清理，可能导致下一个请求读到上一个用户的信息。
     */
    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        UserContext.clear();
    }

    /**
     * SseEmitter 等异步请求会在响应完成前把容器线程归还线程池；此回调就是同步请求线程
     * 与异步处理之间的安全边界，因此不能只依赖最终的 afterCompletion。
     */
    @Override
    public void afterConcurrentHandlingStarted(
            HttpServletRequest request,
            HttpServletResponse response,
            Object handler) {
        UserContext.clear();
    }
}
