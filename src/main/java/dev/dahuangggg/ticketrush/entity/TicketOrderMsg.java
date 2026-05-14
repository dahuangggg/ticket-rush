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
 * 消费者处理每条抢票消息前先写入此表；message_id 唯一索引是幂等门卫：
 * 插入成功则继续创单，DuplicateKeyException 则跳过，防止 Kafka 重试导致重复下单。
 * status: 0 待处理 / 1 成功 / 2 失败
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("tb_ticket_order_msg")
public class TicketOrderMsg {

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
