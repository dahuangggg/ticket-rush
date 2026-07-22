-- STOP-THE-WORLD PROTOCOL BARRIER (cannot be enforced by this SQL file): first reject new rush
-- traffic while V3 consumers and lifecycle workers drain ticket.rush.requests (including legacy
-- payloads), pending orders, and rollback tasks. Then stop every V3 application instance and its
-- legacy rollback worker, verify consumer lag is zero, and start Flyway. Old nodes must remain stopped
-- until V4 and the V4 runtime are fully started. A point-in-time SQL check cannot prevent an old node
-- from inserting new work after the check passes.
--
-- Preflight every MySQL data condition that can be enforced after that barrier. The legacy rollback
-- worker did not create a Redis Reservation hash, so unresolved tasks must be drained by the old
-- worker. Legacy PENDING orders must also reach PAID or a terminal state under V3: if they were
-- canceled by V4, the new Release Intent would have no Redis Reservation hash to release. Finally,
-- the old unique key allowed one pending and one paid order for the same user/SKU; an operator must
-- resolve that invalid dual-active state before the new merged guard. Run all checks before persistent
-- DDL because MySQL DDL commits implicitly: a failed guard must leave the schema itself rerunnable.
CREATE TEMPORARY TABLE v4_upgrade_guard (
    unresolved_count BIGINT NOT NULL,
    legacy_pending_order_count BIGINT NOT NULL,
    duplicate_active_order_count BIGINT NOT NULL,
    CONSTRAINT chk_v4_legacy_rollback_drained CHECK (unresolved_count = 0),
    CONSTRAINT chk_v4_no_legacy_pending_order CHECK (legacy_pending_order_count = 0),
    CONSTRAINT chk_v4_single_active_order CHECK (duplicate_active_order_count = 0)
);

INSERT INTO v4_upgrade_guard (
    unresolved_count,
    legacy_pending_order_count,
    duplicate_active_order_count
)
SELECT
    (SELECT COUNT(*) FROM tb_ticket_rollback_task WHERE status <> 1),
    -- Include soft-deleted rows: V3 inventory may still have been held even when the row is hidden.
    (SELECT COUNT(*) FROM tb_ticket_order WHERE status = 0),
    (SELECT COUNT(*)
       FROM (
            SELECT user_id, sku_id
              FROM tb_ticket_order
             WHERE deleted = 0
               AND status IN (0, 1)
             GROUP BY user_id, sku_id
            HAVING COUNT(*) > 1
       ) duplicate_active_orders);

DROP TEMPORARY TABLE v4_upgrade_guard;

-- Stock opening state: 0=NEW, 1=OPENED, 2=OPENING (retryable cross-system preparation).
ALTER TABLE tb_ticket_sku
    ADD COLUMN stock_initialized TINYINT NOT NULL DEFAULT 0 AFTER stock;

-- Every row that existed before V4 may already have served live traffic. Mark it conservatively
-- so a later Redis loss cannot make the new runtime reopen configured capacity as a first init.
-- Dev seed rows are inserted after V4 and therefore keep the default 0 until explicitly opened.
UPDATE tb_ticket_sku
   SET stock_initialized = 1;

CREATE TABLE tb_rush_reservation (
    id BIGINT NOT NULL PRIMARY KEY,
    reservation_id VARCHAR(96) NOT NULL,
    user_id BIGINT NOT NULL,
    event_id BIGINT NOT NULL,
    sku_id BIGINT NOT NULL,
    quantity INT NOT NULL DEFAULT 1,
    unit_price BIGINT NOT NULL COMMENT 'price snapshot in cents',
    status TINYINT NOT NULL,
    order_id BIGINT NULL,
    reject_reason VARCHAR(1024) NULL,
    deleted TINYINT NOT NULL DEFAULT 0,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_rush_reservation_id (reservation_id),
    KEY idx_reservation_sku_status (sku_id, status),
    KEY idx_reservation_user_time (user_id, create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

ALTER TABLE tb_ticket_order
    ADD COLUMN reservation_id VARCHAR(96) NULL AFTER order_no;

UPDATE tb_ticket_order
   SET reservation_id = CONCAT('legacy-order-', id)
 WHERE reservation_id IS NULL;

INSERT INTO tb_rush_reservation (
    id, reservation_id, user_id, event_id, sku_id, quantity, unit_price,
    status, order_id, deleted, create_time, update_time
)
SELECT id, reservation_id, user_id, event_id, sku_id, quantity,
       CASE WHEN quantity > 0 THEN total_amount DIV quantity ELSE total_amount END,
       CASE status WHEN 0 THEN 2 WHEN 1 THEN 3 ELSE 5 END,
       id, deleted, create_time, update_time
  FROM tb_ticket_order;

ALTER TABLE tb_ticket_order
    MODIFY COLUMN reservation_id VARCHAR(96) NOT NULL,
    DROP INDEX uk_user_sku_active_status,
    DROP COLUMN active_status,
    ADD COLUMN active_order_guard TINYINT GENERATED ALWAYS AS (
        CASE WHEN deleted = 0 AND status IN (0, 1) THEN 1 ELSE NULL END
    ) STORED AFTER cancel_time,
    ADD UNIQUE KEY uk_ticket_order_reservation (reservation_id),
    ADD UNIQUE KEY uk_user_sku_active_order (user_id, sku_id, active_order_guard),
    ADD KEY idx_ticket_order_status_create_id (status, create_time, id);

ALTER TABLE tb_ticket_order_msg
    ADD COLUMN reservation_id VARCHAR(96) NULL AFTER message_id,
    ADD COLUMN order_id BIGINT NULL AFTER quantity;

UPDATE tb_ticket_order_msg
   SET reservation_id = CONCAT('legacy-msg-', id)
 WHERE reservation_id IS NULL;

ALTER TABLE tb_ticket_order_msg
    MODIFY COLUMN reservation_id VARCHAR(96) NOT NULL;

CREATE TABLE tb_inventory_release_intent (
    id BIGINT NOT NULL PRIMARY KEY,
    reservation_id VARCHAR(96) NOT NULL,
    order_id BIGINT NULL,
    user_id BIGINT NOT NULL,
    sku_id BIGINT NOT NULL,
    reason VARCHAR(128) NOT NULL,
    status TINYINT NOT NULL DEFAULT 0,
    retry_count INT NOT NULL DEFAULT 0,
    error_message VARCHAR(1024) NULL,
    deleted TINYINT NOT NULL DEFAULT 0,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_release_intent_reservation (reservation_id),
    KEY idx_release_intent_status_time (status, create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- Keep completed legacy evidence for audit, but the V4 runtime never processes this archive.
RENAME TABLE tb_ticket_rollback_task TO tb_ticket_rollback_task_legacy_archive;

-- Preserve V3 history while bringing IDs back to the application-assigned Snowflake convention.
ALTER TABLE tb_rush_reminder
    MODIFY COLUMN id BIGINT NOT NULL;
