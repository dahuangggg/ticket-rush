package dev.dahuangggg.ticketrush.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 票种SKU实体，对应数据库 tb_ticket_sku。
 *
 * 描述演唱会/演出的不同票种信息（如站票、VIP票），包括定价、库存、售卖时间等。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("tb_ticket_sku")
public class TicketSku {

    public static final int STATUS_NOT_STARTED = 0;
    public static final int STATUS_ON_SALE = 1;
    public static final int STATUS_SOLD_OUT = 2;

    public static final int STOCK_NEW = 0;
    public static final int STOCK_OPENED = 1;
    public static final int STOCK_OPENING = 2;

    /**
     * 票种 SKU 主键。
     *
     * 使用 MyBatis-Plus 的 ASSIGN_ID，由框架生成雪花 ID。
     */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 所属事件 ID。
     */
    private Long eventId;

    /**
     * 票种名称，如"站票"、"VIP票"。
     */
    private String name;

    /**
     * 票价，单位为分。例如 58000 表示 ¥580。
     */
    private Long price;

    /**
     * 数据库库存。
     *
     * Module 4 会在 Redis 维护一个独立的实时库存计数器。
     */
    private Integer stock;

    /** 0=从未开放；2=首次开放进行中、可重试；1=已开放、key 缺失必须按恢复流程处理。 */
    private Integer stockInitialized;

    /**
     * 售卖开始时间。
     */
    private LocalDateTime saleStartTime;

    /**
     * 售卖结束时间。
     */
    private LocalDateTime saleEndTime;

    /**
     * 单用户购买上限。
     */
    private Integer limitPerUser;

    /**
     * 票种状态。
     * 0: 未开始
     * 1: 售卖中
     * 2: 已售罄
     *
     * 实时可售数量以 Redis stock 为准；当前运行时不会自动把数据库状态写成 2，
     * status=2 只表示目录侧显式维护的售罄状态。
     */
    private Integer status;

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
     * 标记为 INSERT 自动填充，新票种插入数据库时由 MyBatisPlusMetaObjectHandler 写入当前时间。
     */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    /**
     * 更新时间。
     *
     * 新增时填当前时间，后续 updateById 等更新操作时也会刷新为当前时间。
     */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
