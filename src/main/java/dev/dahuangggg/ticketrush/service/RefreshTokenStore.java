package dev.dahuangggg.ticketrush.service;

/**
 * refreshToken 存储接口。
 *
 * refreshToken 是一种长效凭证，仅用于在 accessToken 过期后换取新的 accessToken。
 * 与 accessToken（纯 JWT、无状态）不同，refreshToken 存储在 Redis 中，
 * 因此可以随时主动删除，从而实现真正的"退出登录"。
 *
 * 当前实现使用 Redis；测试环境可以替换成内存 Fake。
 */
public interface RefreshTokenStore {

    /**
     * 为指定用户签发一个新的 refreshToken 并保存。
     *
     * 返回生成的 token 字符串，由 AuthService 在登录成功后返回给客户端。
     * 客户端需要持久化保存这个 token（如 localStorage 或 Keychain）。
     */
    String issue(Long userId);

    /**
     * 根据 refreshToken 查询对应的 userId。
     *
     * 如果 Redis 中找不到该 token（token 不存在或已过期），返回 null。
     * 调用方应将 null 视为"登录凭证无效"，抛出 InvalidRefreshTokenException。
     */
    Long getUserId(String token);

    /**
     * 删除 refreshToken。
     *
     * 用户主动退出登录时调用，token 立即从 Redis 中移除。
     * 此后任何使用该 token 换取 accessToken 的请求都会失败，返回 401。
     */
    void delete(String token);
}
