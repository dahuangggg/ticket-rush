package dev.dahuangggg.ticketrush.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 事件实体，对应数据库 tb_event。
 *
 * 描述演唱会/演出事件的基本信息、售卖状态和缓存热点标记。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("tb_event")
public class Event {

    /**
     * 事件主键。
     *
     * 使用 MyBatis-Plus 的 ASSIGN_ID，由框架生成雪花 ID。
     */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 事件标题。
     */
    private String title;

    /**
     * 艺术家/演出者名称。
     */
    private String artist;

    /**
     * 城市。
     */
    private String city;

    /**
     * 演出场所/会场。
     */
    private String venue;

    /**
     * 事件举办时间。
     */
    private LocalDateTime eventTime;

    /**
     * 封面图 URL。
     */
    private String coverUrl;

    /**
     * 事件描述。
     */
    private String description;

    /**
     * 事件状态。
     * 0: 未上架
     * 1: 售卖中
     * 2: 已结束
     */
    private Integer status;

    /**
     * 热点标记。
     * 0: 普通
     * 1: 热点
     *
     * 由运营人员在管理后台标记，标记后系统自动触发缓存预热。
     * 同时用于业务展示（列表置顶、热门标签）和缓存策略选择（逻辑过期 vs Cache-Aside）。
     */
    private Integer isHot;

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
     * 标记为 INSERT 自动填充，新事件插入数据库时由 MyBatisPlusMetaObjectHandler 写入当前时间。
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
