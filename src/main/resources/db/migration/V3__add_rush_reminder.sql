CREATE TABLE tb_rush_reminder (
  id            BIGINT       PRIMARY KEY AUTO_INCREMENT,
  user_id       BIGINT       NOT NULL,
  sku_id        BIGINT       NOT NULL,
  event_id      BIGINT       NOT NULL,
  lead_minutes  INT          NOT NULL,
  trigger_at    DATETIME(3)  NOT NULL,
  status        VARCHAR(16)  NOT NULL,
  read_flag     TINYINT      NOT NULL DEFAULT 0,
  deleted       TINYINT      NOT NULL DEFAULT 0,
  create_time   DATETIME(3)  NOT NULL,
  update_time   DATETIME(3)  NOT NULL,
  UNIQUE KEY uk_user_sku (user_id, sku_id),
  KEY idx_status_trigger (status, trigger_at),
  KEY idx_user_status (user_id, status, read_flag)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='抢票开抢提醒（按 SKU 维度）';
