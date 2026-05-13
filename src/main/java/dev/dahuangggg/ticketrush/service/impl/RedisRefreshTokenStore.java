package dev.dahuangggg.ticketrush.service.impl;

import dev.dahuangggg.ticketrush.service.RefreshTokenStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;

@Component
public class RedisRefreshTokenStore implements RefreshTokenStore {

    /*
     * Redis key 设计：
     * - 前缀 auth:refresh-token: 便于在 Redis 中统一定位刷新令牌数据。
     * - token 本身（UUID）作为 key 后缀，值存储对应的 userId 字符串。
     * - 通过 token 反查 userId 是 O(1) 操作，性能足够。
     *
     * 示例：
     * auth:refresh-token:550e8400-e29b-41d4-a716-446655440000 -> 1001
     */
    private static final String KEY_PREFIX = "auth:refresh-token:";

    private final StringRedisTemplate redisTemplate;

    /*
     * refreshToken 有效期。
     *
     * 默认 14 天，可通过环境变量 REFRESH_TOKEN_TTL 覆盖：
     * ticket-rush.auth.refresh-token-ttl=14d
     *
     * 14 天内用户只要有任何登录操作（调用 /refresh 换新 accessToken），
     * 就不会被强制退出；14 天完全不活跃才真正过期。
     */
    @Value("${ticket-rush.auth.refresh-token-ttl:14d}")
    private Duration refreshTokenTtl;

    public RedisRefreshTokenStore(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * 生成 UUID 作为 refreshToken，写入 Redis 并设置 TTL。
     *
     * 选用 UUID（随机不透明字符串）而非 JWT 的原因：
     * 1. refreshToken 的唯一作用是"换新 accessToken"，不需要携带任何 payload。
     * 2. 存储在 Redis 中意味着可以随时通过 delete 主动撤销，实现真正的注销；
     *    JWT 由于无状态，服务端无法主动让它失效，需要额外维护黑名单。
     * 3. UUID 无法被解码，攻击者即使拿到 token 字符串也无法推断出 userId 等信息。
     */
    @Override
    public String issue(Long userId) {
        String token = UUID.randomUUID().toString();
        redisTemplate.opsForValue().set(key(token), String.valueOf(userId), refreshTokenTtl);
        return token;
    }

    /**
     * 根据 token 查询 userId。
     *
     * Redis key 不存在时返回 null，表示 token 不合法或已过期。
     */
    @Override
    public Long getUserId(String token) {
        String userId = redisTemplate.opsForValue().get(key(token));
        return userId == null ? null : Long.parseLong(userId);
    }

    /**
     * 续期 refreshToken，将 TTL 重置为初始有效期。
     *
     * 使用 Redis EXPIRE 命令直接刷新已有 key 的过期时间，
     * token 字符串本身不变，前端无需更新本地存储。
     */
    @Override
    public void touch(String token) {
        redisTemplate.expire(key(token), refreshTokenTtl);
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
        redisTemplate.delete(key(token));
    }

    private String key(String token) {
        return KEY_PREFIX + token;
    }
}
