package dev.dahuangggg.ticketrush.service;

import dev.dahuangggg.ticketrush.dto.user.UserProfileResponse;
import dev.dahuangggg.ticketrush.entity.User;

/**
 * 用户业务接口。
 *
 * 1. 登录成功后，按手机号查找或自动创建用户。
 * 2. 根据当前登录用户 ID 查询用户资料。
 */
public interface UserService {

    User findOrCreateByPhone(String phone);

    UserProfileResponse getProfile(Long userId);

    /**
     * 根据用户 ID 查询用户实体。
     *
     * 主要用于 refreshToken 换新 accessToken 的场景：
     * Redis 里只存了 userId，需要通过这个方法把完整的 User 对象取出来，
     * 再交给 JwtTokenService 签发包含 phone 等信息的新 accessToken。
     *
     * 返回 null 表示用户不存在（账号被删除等异常情况）。
     */
    User findById(Long userId);
}
