package dev.dahuangggg.ticketrush.security;

/**
 * 当前线程的登录用户上下文。
 *
 * Spring MVC 一个请求通常由一个工作线程处理。
 * JWT 拦截器在请求进入 Controller 前解析 token，并把登录用户放进 ThreadLocal；
 * Controller 或 Service 在同一个请求线程里就可以读取当前用户。
 *
 * 请求结束后必须调用 clear，避免 Tomcat 线程复用时把上一个用户的信息带到下一个请求。
 */
public final class UserContext {

    private static final ThreadLocal<LoginUser> HOLDER = new ThreadLocal<>();

    private UserContext() {
    }

    public static void set(LoginUser loginUser) {
        HOLDER.set(loginUser);
    }

    public static LoginUser get() {
        return HOLDER.get();
    }

    public static Long getUserId() {
        LoginUser loginUser = HOLDER.get();
        return loginUser == null ? null : loginUser.userId();
    }

    public static void clear() {
        HOLDER.remove();
    }
}
