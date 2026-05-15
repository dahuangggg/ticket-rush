package dev.dahuangggg.ticketrush.security;

import dev.dahuangggg.ticketrush.entity.User;
import dev.dahuangggg.ticketrush.exception.UnauthorizedException;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;

@Service
public class JwtTokenService {

    @Value("${ticket-rush.jwt.issuer}")
    private String issuer;

    @Value("${ticket-rush.jwt.secret}")
    private String secret;

    @Value("${ticket-rush.jwt.access-token-ttl}")
    private Duration accessTokenTtl;

    private final Clock clock = Clock.systemUTC();
    private SecretKey signingKey;

    @PostConstruct
    void init() {
        signingKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 给登录成功的手机号签发 JWT。
     *
     * token 中包含：
     * - iss：签发者。
     * - sub：主体，这里用用户 ID，方便后续业务表用 user_id 关联。
     * - userId：自定义 claim，和 sub 保持一致，方便代码读取。
     * - phone：自定义 claim，方便后续需要展示或日志追踪手机号。
     * - iat：签发时间。
     * - exp：过期时间。
     *
     * 这个 token 就是 JWT 模式下的“登录态凭证”。
     * 服务端不保存 Session，每次请求都通过验证这个 token 来确认用户身份。
     */
    public TokenPair issueAccessToken(User user) {
        Instant issuedAt = clock.instant();
        Instant expiresAt = issuedAt.plus(accessTokenTtl);
        String token = Jwts.builder()
                .issuer(issuer)
                .subject(String.valueOf(user.getId()))
                .claim("userId", user.getId())
                .claim("phone", user.getPhone())
                .claim("role", user.getRole() != null ? user.getRole() : "user")
                .issuedAt(Date.from(issuedAt))
                .expiration(Date.from(expiresAt))
                .signWith(signingKey)
                .compact();

        return new TokenPair(token, accessTokenTtl.toSeconds());
    }

    /**
     * 解析并校验 accessToken。
     *
     * 校验内容包括：
     * 1. token 是否由当前 secret 签名。
     * 2. iss 是否等于当前服务配置的 issuer。
     * 3. exp 是否过期。
     * 4. userId/phone 这类业务身份字段是否存在。
     *
     * 只要有任何一步失败，都抛出 UnauthorizedException，
     * 由统一异常处理器返回 401。
     */
    public LoginUser parseAccessToken(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(signingKey)
                    .requireIssuer(issuer)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();

            Long userId = claims.get("userId", Long.class);
            String phone = claims.get("phone", String.class);
            String role = claims.get("role", String.class);
            if (userId == null || phone == null || phone.isBlank()) {
                throw new UnauthorizedException("登录状态无效");
            }
            return new LoginUser(userId, phone, role != null ? role : "user");
        } catch (JwtException | IllegalArgumentException exception) {
            throw new UnauthorizedException("登录状态已过期或无效");
        }
    }

    /**
     * JWT 签发结果。
     *
     * accessToken 是真正给前端保存的 token；
     * expiresIn 是剩余有效期，单位为秒，方便前端判断什么时候刷新或重新登录。
     */
    public record TokenPair(String accessToken, long expiresIn) {
    }
}
