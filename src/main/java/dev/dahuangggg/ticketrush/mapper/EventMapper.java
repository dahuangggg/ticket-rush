package dev.dahuangggg.ticketrush.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import dev.dahuangggg.ticketrush.entity.Event;
import org.apache.ibatis.annotations.Mapper;

/**
 * 事件表 Mapper。
 *
 * 继承 BaseMapper 后，MyBatis-Plus 会提供常用 CRUD 方法：
 * selectById、selectOne、insert、updateById 等。
 */
@Mapper
public interface EventMapper extends BaseMapper<Event> {
}
