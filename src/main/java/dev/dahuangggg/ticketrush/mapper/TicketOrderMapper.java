package dev.dahuangggg.ticketrush.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import dev.dahuangggg.ticketrush.entity.TicketOrder;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

/**
 * TicketOrder 的 Mapper 接口。
 *
 * 继承 MyBatis-Plus 的 BaseMapper，自动获得基础的 CRUD 能力。
 */
@Mapper
public interface TicketOrderMapper extends BaseMapper<TicketOrder> {

    @Select("""
            SELECT COALESCE(SUM(quantity), 0)
              FROM tb_ticket_order
             WHERE sku_id = #{skuId}
               AND status IN (0, 1)
               AND deleted = 0
            """)
    long sumActiveQuantity(Long skuId);

    /**
     * 用 (create_time,id) keyset 翻页；即使整页都是 poison row，本轮也能继续扫描后续订单。
     * 返回的 TicketOrder 只填充 id/createTime 两个投影字段。
     */
    @Select("""
            <script>
            SELECT id, create_time
              FROM tb_ticket_order
             WHERE status = #{status}
               AND create_time &lt; #{deadline}
               AND deleted = 0
            <if test="afterCreateTime != null">
               AND (create_time &gt; #{afterCreateTime}
                    OR (create_time = #{afterCreateTime} AND id &gt; #{afterId}))
            </if>
             ORDER BY create_time, id
             LIMIT #{limit}
            </script>
            """)
    List<TicketOrder> selectTimeoutCandidates(@Param("status") int status,
                                              @Param("deadline") LocalDateTime deadline,
                                              @Param("afterCreateTime") LocalDateTime afterCreateTime,
                                              @Param("afterId") Long afterId,
                                              @Param("limit") int limit);
}
