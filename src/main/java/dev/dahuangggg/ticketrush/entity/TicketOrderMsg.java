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
 * Kafka 消息追踪实体，对应数据库 tb_ticket_order_msg。
 *
 * 消息记录、订单和 SUCCESS 状态必须在同一个本地事务中提交。
 * 只有已经 SUCCESS 的重复消息才允许直接跳过。
 * status: 0 待处理 / 1 成功 / 2 失败
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("tb_ticket_order_msg")
public class TicketOrderMsg {

    public static final int STATUS_PENDING = 0;
    public static final int STATUS_SUCCESS = 1;
    public static final int STATUS_FAILED = 2;

    /**
     * 主键。
     *
     * 使用 MyBatis-Plus 的 ASSIGN_ID，由框架生成雪花 ID。
     */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * Kafka 消息 ID（来自 TicketRushMessage.messageId），唯一索引。
     *
     * 重复消费时此字段触发 DuplicateKeyException，实现幂等。
     */
    private String messageId;

    /** 对应的业务 Reservation ID。 */
    private String reservationId;

    /**
     * 用户 ID。
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
     * 购票数量。
     */
    private Integer quantity;

    /** 成功创建的订单 ID；PENDING/FAILED 时为空。 */
    private Long orderId;

    /**
     * 处理状态。
     *
     * 0 待处理 / 1 成功 / 2 失败
     */
    private Integer status;

    /**
     * 失败原因，成功时为 null。
     */
    private String errorMessage;

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
     * 标记为 INSERT 自动填充，新消息记录插入数据库时由 MyBatisPlusMetaObjectHandler 写入当前时间。
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
