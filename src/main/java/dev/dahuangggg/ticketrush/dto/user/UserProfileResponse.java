package dev.dahuangggg.ticketrush.dto.user;

/**
 * 当前登录用户资料响应。
 *
 * 不直接返回 User 实体，是为了避免把数据库字段完整暴露给前端。
 * 例如 deleted、updateTime 这类内部字段不应该出现在用户资料接口里。
 */
public record UserProfileResponse(
        Long id,
        String phone,
        String nickName,
        String icon
) {
}
