package dev.dahuangggg.ticketrush.config;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Component
public class MybatisPlusMetaObjectHandler implements MetaObjectHandler {

    /**
     * 插入数据时自动填充公共时间字段。
     *
     * 只要实体字段使用：
     * @TableField(fill = FieldFill.INSERT)
     * @TableField(fill = FieldFill.INSERT_UPDATE)
     *
     * MyBatis-Plus 在 insert 前就会调用这个方法。
     * 这样新用户注册时，即使业务代码没有手动设置 createTime/updateTime，
     * 数据库里也会写入当前应用时间。
     */
    @Override
    public void insertFill(MetaObject metaObject) {
        LocalDateTime now = LocalDateTime.now();
        strictInsertFill(metaObject, "createTime", LocalDateTime.class, now);
        strictInsertFill(metaObject, "updateTime", LocalDateTime.class, now);
    }

    /**
     * 更新数据时自动刷新 updateTime。
     *
     * 后续如果更新用户昵称、头像，或者更新订单状态，
     * 只要实体字段标了 FieldFill.INSERT_UPDATE，就会自动维护 updateTime。
     */
    @Override
    public void updateFill(MetaObject metaObject) {
        strictUpdateFill(metaObject, "updateTime", LocalDateTime.class, LocalDateTime.now());
    }
}
