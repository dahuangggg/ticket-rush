package dev.dahuangggg.ticketrush.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import dev.dahuangggg.ticketrush.entity.TicketSku;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 票种 SKU 表 Mapper。
 *
 * 继承 BaseMapper 后，MyBatis-Plus 会提供常用 CRUD 方法：
 * selectById、selectOne、insert、updateById 等。
 */
public interface TicketSkuMapper extends BaseMapper<TicketSku> {

    /**
     * Redis outbox 发现索引的 durable 重建源。包含软删除行，因为删除前接受的 Journal
     * 仍必须进入 Ledger；OPENING 尚未激活真实售卖状态，不会产生 Journal。
     */
    @Select("SELECT id FROM tb_ticket_sku WHERE stock_initialized = 1 ORDER BY id")
    List<Long> selectOpenedSkuIdsForOutboxDiscovery();

    /** 首次开放先进入可恢复的 OPENING，不能在 Redis 写成功前直接声称 OPENED。 */
    @Update("""
            UPDATE tb_ticket_sku
               SET stock_initialized = 2,
                   update_time = CURRENT_TIMESTAMP
             WHERE id = #{skuId}
               AND deleted = 0
               AND stock_initialized = 0
            """)
    int claimStockOpeningIfNew(@Param("skuId") Long skuId);

    /** Redis 已以不可售元数据准备完成后，把 OPENING 收口为 OPENED。 */
    @Update("""
            UPDATE tb_ticket_sku
               SET stock_initialized = 1,
                   update_time = CURRENT_TIMESTAMP
             WHERE id = #{skuId}
               AND deleted = 0
               AND stock_initialized = 2
            """)
    int markStockOpenedIfOpening(@Param("skuId") Long skuId);
}
