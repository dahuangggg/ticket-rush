package dev.dahuangggg.ticketrush.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import dev.dahuangggg.ticketrush.dto.user.UserProfileResponse;
import dev.dahuangggg.ticketrush.entity.User;
import dev.dahuangggg.ticketrush.exception.UnauthorizedException;
import dev.dahuangggg.ticketrush.mapper.UserMapper;
import dev.dahuangggg.ticketrush.service.UserService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

@Service
public class UserServiceImpl implements UserService {

    private final UserMapper userMapper;

    public UserServiceImpl(UserMapper userMapper) {
        this.userMapper = userMapper;
    }

    /**
     * 根据手机号查询用户；如果不存在，则自动创建用户。
     *
     * 这个设计对应“手机号验证码登录 = 注册/登录一体化”：
     * - 老用户：手机号已存在，直接登录。
     * - 新用户：手机号第一次登录，自动注册一条 tb_user 记录。
     *
     * 数据库的 uk_user_phone 唯一索引是最后防线，保证并发登录时不会创建多个同手机号用户。
     */
    @Override
    public User findOrCreateByPhone(String phone) {
        User existingUser = userMapper.selectOne(new LambdaQueryWrapper<User>()
                .eq(User::getPhone, phone)
                .last("LIMIT 1"));
        if (existingUser != null) {
            return existingUser;
        }

        User newUser = User.builder()
                .phone(phone)
                .nickName("用户" + phone.substring(phone.length() - 4))
                .icon("")
                .build();
        try {
            userMapper.insert(newUser);
        } catch (DuplicateKeyException e) {
            // 并发竞态, 捕获 DuplicateKeyException，然后重新查询返回已存在的用户。
            return userMapper.selectOne(new LambdaQueryWrapper<User>()
                    .eq(User::getPhone, phone).last("LIMIT 1"));
        }
        return newUser;
    }

    /**
     * 根据用户 ID 查询用户实体。
     *
     * 返回 null 表示用户不存在，调用方自行决定如何处理。
     * 当前在 refreshToken 换新 accessToken 流程中使用。
     */
    @Override
    public User findById(Long userId) {
        return userMapper.selectById(userId);
    }

    /**
     * 查询当前登录用户资料。
     *
     * JWT 只能证明“请求声称的用户 ID 是签名可信的”，但用户资料仍然以数据库为准。
     * 如果数据库里查不到用户，说明账号可能被删除或 token 已经不再适用，返回 401。
     */
    @Override
    public UserProfileResponse getProfile(Long userId) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new UnauthorizedException("用户不存在或登录状态已失效");
        }
        return new UserProfileResponse(user.getId(), user.getPhone(), user.getNickName(), user.getIcon());
    }
}
