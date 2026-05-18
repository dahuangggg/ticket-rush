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

/** AI 助手设置的开抢提醒，对应 tb_rush_reminder。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("tb_rush_reminder")
public class RushReminder {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_FIRED = "FIRED";
    public static final String STATUS_CANCELLED = "CANCELLED";

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long userId;
    private Long skuId;
    private Long eventId;
    private Integer leadMinutes;
    private LocalDateTime triggerAt;
    private String status;
    private Integer readFlag;

    @TableLogic
    private Integer deleted;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
