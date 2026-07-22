package dev.dahuangggg.ticketrush.service.impl;

import dev.dahuangggg.ticketrush.infrastructure.redis.RedisKeyRegistry;
import dev.dahuangggg.ticketrush.service.RefreshTokenStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

@Component
public class RedisRefreshTokenStore implements RefreshTokenStore {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final Base64.Encoder TOKEN_ENCODER = Base64.getUrlEncoder().withoutPadding();

    /*
     * Redis key 设计：
     * - 前缀 auth:refresh-token: 便于在 Redis 中统一定位刷新令牌数据。
     * - token 的 SHA-256 摘要作为 key 后缀，值存储对应的 userId 字符串。
     * - 通过 token 摘要反查 userId 是 O(1) 操作，性能足够。
     *
     * 示例：
     * auth:refresh-token:8f3c...<64 hex chars> -> 1001
     */
    private final StringRedisTemplate redisTemplate;
    private final Duration refreshTokenTtl;

    public RedisRefreshTokenStore(StringRedisTemplate redisTemplate,
                                  @Value("${ticket-rush.auth.refresh-token-ttl:14d}") Duration refreshTokenTtl) {
        this.redisTemplate = redisTemplate;
        this.refreshTokenTtl = refreshTokenTtl;
    }

    /**
     * 生成 256 bit 随机 refreshToken，写入 Redis 并设置固定 TTL。
     *
     * 选用随机不透明字符串而非 JWT 的原因：
     * 1. refreshToken 的唯一作用是"换新 accessToken"，不需要携带任何 payload。
     * 2. 存储在 Redis 中意味着可以随时通过 delete 主动撤销，实现真正的注销；
     *    JWT 由于无状态，服务端无法主动让它失效，需要额外维护黑名单。
     * 3. Redis key 只保存 token 的 SHA-256 摘要。即使 Redis 数据泄露，也不会直接暴露
     *    可以拿去调用 /refresh 的 bearer credential。
     */
    @Override
    public String issue(Long userId) {
        byte[] randomBytes = new byte[32];
        SECURE_RANDOM.nextBytes(randomBytes);
        String token = TOKEN_ENCODER.encodeToString(randomBytes);
        redisTemplate.opsForValue().set(tokenKey(token), String.valueOf(userId), refreshTokenTtl);
        return token;
    }

    /**
     * 根据 token 查询 userId。
     *
     * Redis key 不存在时返回 null，表示 token 不合法或已过期。
     */
    @Override
    public Long getUserId(String token) {
        String userId = redisTemplate.opsForValue().get(tokenKey(token));
        return userId == null ? null : Long.parseLong(userId);
    }

    /**
     * 删除 refreshToken，使其立即失效。
     *
     * 注意：这里只删除了 Redis 中的 refreshToken。
     * 与它配套的 accessToken 仍然有效，直到自然过期（最多 2 小时）。
     * 对于抢票平台这个窗口期是可以接受的；
     * 如需立即吊销 accessToken，需额外维护 accessToken 黑名单。
     */
    @Override
    public void delete(String token) {
        redisTemplate.delete(tokenKey(token));
    }

    private static String tokenKey(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8));
            return RedisKeyRegistry.refreshTokenKey(java.util.HexFormat.of().formatHex(digest));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by the Java platform", impossible);
        }
    }
}
