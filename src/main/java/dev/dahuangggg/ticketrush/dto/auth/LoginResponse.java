package dev.dahuangggg.ticketrush.dto.auth;

public record LoginResponse(
        String accessToken,
        String tokenType,
        long expiresIn,
        /*
         * refreshToken 是长效凭证，有效期默认 14 天。
         * 客户端应持久化保存（如 localStorage 或 Keychain），
         * 在 accessToken 过期后用它调用 POST /api/auth/refresh 换取新的 accessToken。
         */
        String refreshToken
) {
}
