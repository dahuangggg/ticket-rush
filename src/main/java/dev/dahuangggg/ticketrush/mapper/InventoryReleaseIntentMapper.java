package dev.dahuangggg.ticketrush.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import dev.dahuangggg.ticketrush.entity.InventoryReleaseIntent;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface InventoryReleaseIntentMapper extends BaseMapper<InventoryReleaseIntent> {

    /** 包含软删除行；恢复屏障不能因隐藏一条未结意图而误判安全。 */
    @Select("""
            SELECT COUNT(*)
              FROM tb_inventory_release_intent
             WHERE sku_id = #{skuId}
               AND status <> 1
            """)
    long countUnsettledForSku(@Param("skuId") Long skuId);

    /** 根据数据库当前 retry_count 原子推进，避免并发 worker 把 PENDING 卡在上限。 */
    @Update("""
            UPDATE tb_inventory_release_intent
               SET status = CASE WHEN retry_count >= #{failureThreshold} THEN 2 ELSE status END,
                   retry_count = LEAST(retry_count + 1, #{maxRetryCount}),
                   error_message = #{errorMessage},
                   update_time = CURRENT_TIMESTAMP
             WHERE id = #{id}
               AND status = 0
               AND retry_count < #{maxRetryCount}
            """)
    int recordFailure(@Param("id") Long id,
                      @Param("errorMessage") String errorMessage,
                      @Param("failureThreshold") int failureThreshold,
                      @Param("maxRetryCount") int maxRetryCount);
}
