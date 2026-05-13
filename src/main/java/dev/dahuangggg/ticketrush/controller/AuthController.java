package dev.dahuangggg.ticketrush.controller;

import dev.dahuangggg.ticketrush.dto.auth.LoginRequest;
import dev.dahuangggg.ticketrush.dto.auth.LoginResponse;
import dev.dahuangggg.ticketrush.dto.auth.LogoutRequest;
import dev.dahuangggg.ticketrush.dto.auth.RefreshRequest;
import dev.dahuangggg.ticketrush.dto.auth.SendSmsCodeRequest;
import dev.dahuangggg.ticketrush.dto.auth.SimpleResponse;
import dev.dahuangggg.ticketrush.service.AuthService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    /**
     * 发送短信验证码。
     *
     * 当前版本为了本地开发方便，不接真实短信服务：
     * - service 会生成 6 位数字验证码；
     * - 验证码会保存到 Redis，并设置过期时间；
     * - 验证码会打印到日志，方便开发阶段复制测试。
     */
    @PostMapping("/sms-code")
    public SimpleResponse sendSmsCode(@Valid @RequestBody SendSmsCodeRequest request) {
        authService.sendSmsCode(request.phone());
        return new SimpleResponse(true, "验证码已发送");
    }

    /**
     * 手机号 + 短信验证码登录。
     *
     * 业务流程：
     * 1. 前端提交手机号和验证码。
     * 2. service 校验 Redis 中保存的验证码。
     * 3. 验证通过后删除验证码，避免同一个验证码被重复使用。
     * 4. 签发短效 accessToken（2 小时）+ 长效 refreshToken（14 天）。
     */
    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request.phone(), request.code());
    }

    /**
     * 使用 refreshToken 换取新的 accessToken。
     *
     * 前端应在 accessToken 过期前（或收到 401 后）调用此接口，
     * 用 refreshToken 换取新的 accessToken，无需用户重新输入手机号和验证码。
     * 此接口不需要携带 Authorization 请求头，由 WebMvcConfig 排除在拦截器之外。
     */
    @PostMapping("/refresh")
    public LoginResponse refresh(@Valid @RequestBody RefreshRequest request) {
        return authService.refresh(request.refreshToken());
    }

    /**
     * 主动退出登录。
     *
     * 删除 Redis 中的 refreshToken，使其立即失效。
     * 前端同时应清空本地存储的 accessToken 和 refreshToken。
     * 此接口不需要携带 Authorization 请求头，由 WebMvcConfig 排除在拦截器之外，
     * 保证 accessToken 已过期的用户也能正常退出。
     */
    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@Valid @RequestBody LogoutRequest request) {
        authService.logout(request.refreshToken());
    }
}
