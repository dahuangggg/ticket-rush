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
 * 用户表实体，对应数据库 tb_user。
 *
 * 这个类只描述数据库中的用户数据，不直接承载登录流程。
 * 登录逻辑放在 AuthServiceImpl，用户查询/创建逻辑放在 UserServiceImpl。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("tb_user")
public class User {

    /**
     * 用户主键。
     *
     * 使用 MyBatis-Plus 的 ASSIGN_ID，由框架生成雪花 ID。
     */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 手机号。
     *
     * 当前模块一使用手机号作为登录账号，并在数据库上用唯一索引保证同一个手机号只对应一个用户。
     */
    private String phone;

    /**
     * 用户昵称。
     *
     * 首次登录自动注册时会生成一个默认昵称，后续可以在用户资料模块中允许用户修改。
     */
    private String nickName;

    /**
     * 用户头像 URL。
     *
     * 当前先允许为空字符串，后续可以接入上传或默认头像。
     */
    private String icon;

    /**
     * 用户角色，默认为 "user"，管理员为 "admin"。
     */
    private String role;

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
     * 标记为 INSERT 自动填充，新注册用户插入数据库时由 MyBatisPlusMetaObjectHandler 写入当前时间。
     * 不放在业务代码里手动 set，是为了后续 Event、Order 等实体也能复用同一套填充规则。
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
