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
 * Reservation Ledger 的持久化实体。
 *
 * <p>Redis 是抢票线性化点；此表由 Redis Outbox Relay 在发送 Kafka 前写入，供订单消费者
 * 校验消息来源，也用于 Redis 恢复与库存对账。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("tb_rush_reservation")
public class RushReservation {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String reservationId;

    private Long userId;

    private Long eventId;

    private Long skuId;

    private Integer quantity;

    /** 预占时的价格快照，单位为分。 */
    private Long unitPrice;

    /** 参见 ReservationStatus.code()。 */
    private Integer status;

    private Long orderId;

    private String rejectReason;

    @TableLogic
    private Integer deleted;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
