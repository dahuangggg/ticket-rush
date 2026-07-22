package dev.dahuangggg.ticketrush.service.impl;

import dev.dahuangggg.ticketrush.dto.auth.LoginResponse;
import dev.dahuangggg.ticketrush.entity.User;
import dev.dahuangggg.ticketrush.exception.InvalidRefreshTokenException;
import dev.dahuangggg.ticketrush.exception.InvalidSmsCodeException;
import dev.dahuangggg.ticketrush.exception.SmsCodeAttemptsExceededException;
import dev.dahuangggg.ticketrush.security.JwtTokenService;
import dev.dahuangggg.ticketrush.service.AuthService;
import dev.dahuangggg.ticketrush.service.RefreshTokenStore;
import dev.dahuangggg.ticketrush.service.SmsCodeStore;
import dev.dahuangggg.ticketrush.service.UserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;

@Service
public class AuthServiceImpl implements AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthServiceImpl.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SmsCodeStore smsCodeStore;
    private final RefreshTokenStore refreshTokenStore;
    private final JwtTokenService jwtTokenService;
    private final UserService userService;

    public AuthServiceImpl(SmsCodeStore smsCodeStore,
                           RefreshTokenStore refreshTokenStore,
                           JwtTokenService jwtTokenService,
                           UserService userService) {
        this.smsCodeStore = smsCodeStore;
        this.refreshTokenStore = refreshTokenStore;
        this.jwtTokenService = jwtTokenService;
        this.userService = userService;
    }

    /**
     * 生成短信验证码并保存。
     *
     * 这里使用 SecureRandom 生成 0 到 999999 之间的随机数，
     * 再用 %06d 补齐成 6 位数字字符串，例如 42 会变成 000042。
     *
     * 当前没有接入真实短信平台，所以验证码只保存到 Redis；日志只记录手机号，
     * 不记录验证码明文，避免共享日志成为新的登录凭证泄露面。
     * 后续接入短信平台时，可以在 smsCodeStore.save 后调用短信供应商 SDK。
     */
    @Override
    public void sendSmsCode(String phone) {
        String code = String.format("%06d", RANDOM.nextInt(1_000_000));
        smsCodeStore.save(phone, code);
        log.info("SMS verification code generated for phone={}", maskPhone(phone));
    }

    /**
     * 使用手机号和短信验证码登录。
     *
     * 关键业务点：
     * 1. 原子校验并消费验证码。正确的验证码也只能被一个并发请求使用一次。
     * 2. 根据手机号查询或创建 tb_user 用户记录。
     * 3. 签发短效 accessToken（JWT）+ 256-bit 加密安全随机 refreshToken。
     * 4. Redis 只保存 refreshToken 的 SHA-256 摘要，避免存储泄露后直接被当作凭证使用。
     * 5. 返回 tokenType=Bearer，前端后续请求可以放到 Authorization 请求头里。
     */
    @Override
    public LoginResponse login(String phone, String code) {
        SmsCodeStore.VerificationResult result = smsCodeStore.verifyAndConsume(phone, code);
        if (result == SmsCodeStore.VerificationResult.TOO_MANY_ATTEMPTS) {
            throw new SmsCodeAttemptsExceededException();
        }
        if (result != SmsCodeStore.VerificationResult.VERIFIED) {
            throw new InvalidSmsCodeException();
        }

        User user = userService.findOrCreateByPhone(phone);

        JwtTokenService.TokenPair tokenPair = jwtTokenService.issueAccessToken(user);
        String refreshToken = refreshTokenStore.issue(user.getId());
        return new LoginResponse(tokenPair.accessToken(), "Bearer", tokenPair.expiresIn(), refreshToken);
    }

    private static String maskPhone(String phone) {
        if (phone == null || phone.length() < 7) {
            return "***";
        }
        return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
    }

    /**
     * 使用 refreshToken 换取新的 accessToken。
     *
     * 业务流程：
     * 1. 从 Redis 中查找 refreshToken 对应的 userId；不存在则视为已过期或已注销。
     * 2. 通过 userId 从数据库加载完整用户信息（refreshToken 只存了 userId，不含 phone 等）。
     * 3. 用户不存在说明账号被删除，同样视为无效登录状态。
     * 4. 重新签发一个新的 accessToken，refreshToken 本身不更新（TTL 不延长）。
     *
     * 注意：refreshToken 的 TTL 是固定的，不会因为每次刷新而重置。
     * 用户如果需要"永不过期"，需要在 refreshToken 即将到期前重新登录。
     * 更高级的实现可以使用“原子轮换 + 重放检测”，但不能只做简单滑动续期，
     * 否则被盗 token 只要持续使用就可能永久有效。
     */
    @Override
    public LoginResponse refresh(String refreshToken) {
        Long userId = refreshTokenStore.getUserId(refreshToken);
        if (userId == null) {
            throw new InvalidRefreshTokenException();
        }

        User user = userService.findById(userId);
        if (user == null) {
            // 账号已被删除，同步清理 Redis 中残留的 refreshToken
            refreshTokenStore.delete(refreshToken);
            throw new InvalidRefreshTokenException();
        }

        JwtTokenService.TokenPair tokenPair = jwtTokenService.issueAccessToken(user);
        return new LoginResponse(tokenPair.accessToken(), "Bearer", tokenPair.expiresIn(), refreshToken);
    }

    /**
     * 主动退出登录。
     *
     * 从 Redis 中删除 refreshToken，之后该 token 无法再用于换取新的 accessToken。
     *
     * 注意：与 refreshToken 配套的 accessToken 是纯 JWT（无状态），
     * 服务端无法主动撤销，它会在自然过期后失效（最多 2 小时窗口期）。
     * 前端在退出时应同时清空本地存储的 accessToken，避免继续使用。
     */
    @Override
    public void logout(String refreshToken) {
        refreshTokenStore.delete(refreshToken);
        log.info("User logged out, refreshToken invalidated");
    }
}
