package dev.dahuangggg.ticketrush.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import dev.dahuangggg.ticketrush.entity.RushReservation;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface RushReservationMapper extends BaseMapper<RushReservation> {

    @Select("""
            SELECT COALESCE(SUM(quantity), 0)
              FROM tb_rush_reservation
             WHERE sku_id = #{skuId}
               AND status IN (0, 1, 4)
               AND deleted = 0
            """)
    long sumHeldQuantity(Long skuId);
}
