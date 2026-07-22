package dev.dahuangggg.ticketrush.config;

import dev.dahuangggg.ticketrush.security.AdminAuthInterceptor;
import dev.dahuangggg.ticketrush.security.JwtAuthenticationInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final JwtAuthenticationInterceptor jwtAuthenticationInterceptor;
    private final AdminAuthInterceptor adminAuthInterceptor;

    public WebMvcConfig(JwtAuthenticationInterceptor jwtAuthenticationInterceptor,
                        AdminAuthInterceptor adminAuthInterceptor) {
        this.jwtAuthenticationInterceptor = jwtAuthenticationInterceptor;
        this.adminAuthInterceptor = adminAuthInterceptor;
    }

    /**
     * 注册拦截器。顺序重要：JWT 拦截器必须先于 Admin 拦截器执行，
     * 因为 AdminAuthInterceptor 依赖 JWT 拦截器设置的 UserContext 来读取角色。
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 1. JWT 登录态拦截器（设置 UserContext）
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

        // 2. 管理员权限拦截器（依赖上面的 UserContext 已设置）
        registry.addInterceptor(adminAuthInterceptor)
                .addPathPatterns("/api/admin/**");
    }
}
