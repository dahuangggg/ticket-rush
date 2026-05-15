package dev.dahuangggg.ticketrush.security;

/**
 * 当前请求中的登录用户信息。
 *
 * 这个对象来自 JWT，而不是每次都查数据库。
 * 拦截器解析 token 后，把 userId 和 phone 放入 UserContext；
 * Controller/Service 如果只需要知道“当前用户是谁”，直接读取这里即可。
 */
public record LoginUser(
        Long userId,
        String phone,
        String role
) {
}
