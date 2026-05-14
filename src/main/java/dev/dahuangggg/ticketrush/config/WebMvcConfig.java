package dev.dahuangggg.ticketrush.config;

import dev.dahuangggg.ticketrush.security.JwtAuthenticationInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final JwtAuthenticationInterceptor jwtAuthenticationInterceptor;

    public WebMvcConfig(JwtAuthenticationInterceptor jwtAuthenticationInterceptor) {
        this.jwtAuthenticationInterceptor = jwtAuthenticationInterceptor;
    }

    /**
     * 注册 JWT 登录态拦截器。
     *
     * 放行认证接口：
     * - POST /api/auth/sms-code 用于获取验证码，还没有 token。
     * - POST /api/auth/login 用于登录换取 token，也还没有 token。
     *
     * 其他 /api/** 接口默认都需要携带 Bearer token。
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(jwtAuthenticationInterceptor)
                .addPathPatterns("/api/**")
                .excludePathPatterns(
                        "/api/auth/sms-code",   // 获取验证码，尚未登录
                        "/api/auth/login",      // 登录，尚未持有 token
                        "/api/auth/refresh",    // 用 refreshToken 换新 accessToken，accessToken 可能已过期
                        "/api/auth/logout",     // 退出登录，保证 accessToken 过期时也能退出
                        "/api/events",          // 活动列表，公开浏览无需登录
                        "/api/events/**",       // 活动详情 + 票档列表（/api/events/{id}/skus），公开浏览无需登录
                        "/api/ticket-skus/**"   // 票档详情，公开浏览无需登录
                );
    }
}
