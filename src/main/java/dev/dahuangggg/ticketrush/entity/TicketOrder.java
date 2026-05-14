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
 * 订单实体，对应数据库 tb_ticket_order。
 *
 * 由 Kafka 消费者在抢票成功后异步创建，初始 status=0（待支付）。
 * status: 0 待支付 / 1 已支付 / 2 已取消 / 3 已超时
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("tb_ticket_order")
public class TicketOrder {

    /**
     * 订单主键。
     *
     * 使用 MyBatis-Plus 的 ASSIGN_ID，由框架生成雪花 ID。
     */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 业务订单号，全局唯一，用于对外展示。
     */
    private String orderNo;

    /**
     * 下单用户 ID。
     */
    private Long userId;

    /**
     * 演出活动 ID。
     */
    private Long eventId;

    /**
     * 票档 SKU ID。
     */
    private Long skuId;

    /**
     * 购票数量，当前固定为 1。
     */
    private Integer quantity;

    /**
     * 订单总金额，单位为分。
     */
    private Long totalAmount;

    /**
     * 订单状态。
     *
     * 0 待支付 / 1 已支付 / 2 已取消 / 3 已超时
     */
    private Integer status;

    /**
     * 支付时间，未支付时为 null。
     */
    private LocalDateTime payTime;

    /**
     * 取消时间，未取消时为 null。
     */
    private LocalDateTime cancelTime;

    /**
     * 逻辑删除字段。
     *
     * MyBatis-Plus 查询时会自动过滤 deleted=1 的数据。
     */
    @TableLogic
    private Integer deleted;

    /**
     * 创建时间。
     *
     * 标记为 INSERT 自动填充，新订单插入数据库时由 MyBatisPlusMetaObjectHandler 写入当前时间。
     */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    /**
     * 更新时间。
     *
     * 新增时填当前时间，后续更新操作时也会刷新为当前时间。
     */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
