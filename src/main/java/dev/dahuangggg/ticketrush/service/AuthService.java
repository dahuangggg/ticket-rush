package dev.dahuangggg.ticketrush.service;

import dev.dahuangggg.ticketrush.dto.auth.LoginResponse;

/**
 * 认证业务接口。
 *
 * Controller 面向这个接口编程，而不是直接依赖 AuthServiceImpl。
 * 这样后续如果要把“本地日志模拟短信”替换成“真实短信平台”，
 * 或者把“短信验证码登录”扩展成“密码登录/微信登录”，Controller 层不用跟着大改。
 */
public interface AuthService {

    /**
     * 给指定手机号生成并保存短信验证码。
     */
    void sendSmsCode(String phone);

    /**
     * 校验手机号和验证码，成功后返回 accessToken + refreshToken。
     */
    LoginResponse login(String phone, String code);

    /**
     * 使用 refreshToken 换取新的 accessToken。
     *
     * refreshToken 有效时，签发一个新的 accessToken 返回给客户端。
     * 当前教学版本选择固定 TTL：refreshToken 本身不更新，TTL 也不会因活跃而延长。
     * 如果 refreshToken 无效或已过期，抛出 InvalidRefreshTokenException → 401。
     */
    LoginResponse refresh(String refreshToken);

    /**
     * 主动退出登录，使 refreshToken 立即失效。
     *
     * 从 Redis 中删除 refreshToken，之后无法再用它换取新的 accessToken。
     * 配套的 accessToken 会在自然过期后失效（最多 2 小时窗口期）。
     */
    void logout(String refreshToken);
}
