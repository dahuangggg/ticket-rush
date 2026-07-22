package dev.dahuangggg.ticketrush.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 与订单状态变更在同一 MySQL 事务中写入的库存释放意图。
 * Worker 可以在任意时刻重放；Redis Reservation 状态机保证释放幂等。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("tb_inventory_release_intent")
public class InventoryReleaseIntent {

    public static final int STATUS_PENDING = 0;
    public static final int STATUS_SUCCESS = 1;
    public static final int STATUS_FAILED = 2;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String reservationId;

    /** 创单前拒绝时可以为空。 */
    private Long orderId;

    private Long userId;

    private Long skuId;

    private String reason;

    private Integer status;

    private Integer retryCount;

    private String errorMessage;

    @TableLogic
    private Integer deleted;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
