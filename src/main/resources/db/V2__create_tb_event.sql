CREATE TABLE tb_event
(
    id          BIGINT       NOT NULL COMMENT 'Snowflake ID',
    title       VARCHAR(200) NOT NULL COMMENT '活动标题，如 周杰伦2026世界巡回演唱会',
    artist      VARCHAR(100) NOT NULL COMMENT '艺人名称',
    city        VARCHAR(50)  NOT NULL COMMENT '演出城市',
    venue       VARCHAR(200) NOT NULL COMMENT '演出场馆',
    event_time  DATETIME     NOT NULL COMMENT '开演时间',
    cover_url   VARCHAR(500) NOT NULL DEFAULT '' COMMENT '封面图 URL',
    description TEXT COMMENT '活动详情描述',
    status      TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '0未上架 1售卖中 2已结束',
    is_hot      TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '0普通活动 1热点活动，运营标记，标记后自动预热缓存',
    deleted     TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '逻辑删除，0正常 1已删除',
    create_time DATETIME     NOT NULL COMMENT '创建时间，自动填充',
    update_time DATETIME     NOT NULL COMMENT '更新时间，自动填充',
    PRIMARY KEY (id),
    INDEX idx_city_status (city, status) COMMENT '按城市和状态过滤',
    INDEX idx_event_time (event_time) COMMENT '按日期过滤'
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='活动表';
