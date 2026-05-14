package dev.dahuangggg.ticketrush.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import dev.dahuangggg.ticketrush.entity.TicketOrder;
import org.apache.ibatis.annotations.Mapper;

/**
 * TicketOrder 的 Mapper 接口。
 *
 * 继承 MyBatis-Plus 的 BaseMapper，自动获得基础的 CRUD 能力。
 */
@Mapper
public interface TicketOrderMapper extends BaseMapper<TicketOrder> {
}
