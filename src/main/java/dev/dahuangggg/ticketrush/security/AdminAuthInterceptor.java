package dev.dahuangggg.ticketrush.security;

import dev.dahuangggg.ticketrush.exception.ForbiddenException;
import dev.dahuangggg.ticketrush.exception.UnauthorizedException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 管理员权限拦截器，保护 /api/admin/** 路径。
 * 在 JwtAuthenticationInterceptor 之后执行，此时 UserContext 已设置。
 */
@Component
public class AdminAuthInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (UserContext.get() == null) {
            throw new UnauthorizedException("请先登录");
        }
        if (!"admin".equals(UserContext.getRole())) {
            throw new ForbiddenException("需要管理员权限");
        }
        return true;
    }
}
