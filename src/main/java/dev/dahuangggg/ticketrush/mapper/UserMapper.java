package dev.dahuangggg.ticketrush.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import dev.dahuangggg.ticketrush.entity.User;

/**
 * 用户表 Mapper。
 *
 * 继承 BaseMapper 后，MyBatis-Plus 会提供常用 CRUD 方法：
 * selectById、selectOne、insert、updateById 等。
 */
public interface UserMapper extends BaseMapper<User> {
}
