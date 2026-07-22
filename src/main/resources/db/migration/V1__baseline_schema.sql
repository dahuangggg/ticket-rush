SET NAMES utf8mb4;

-- Immutable pre-role/pre-reminder baseline. Later changes belong in V2+ migrations.
CREATE TABLE tb_user (
    id BIGINT NOT NULL PRIMARY KEY,
    phone VARCHAR(20) NOT NULL,
    nick_name VARCHAR(64) NOT NULL DEFAULT '',
    icon VARCHAR(512) NOT NULL DEFAULT '',
    deleted TINYINT NOT NULL DEFAULT 0,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_user_phone (phone),
    KEY idx_user_create_time (create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE tb_event (
    id BIGINT NOT NULL PRIMARY KEY,
    title VARCHAR(200) NOT NULL,
    artist VARCHAR(100) NOT NULL,
    city VARCHAR(50) NOT NULL,
    venue VARCHAR(200) NOT NULL,
    event_time DATETIME NOT NULL,
    cover_url VARCHAR(500) NOT NULL DEFAULT '',
    description TEXT NULL,
    status TINYINT NOT NULL DEFAULT 0,
    is_hot TINYINT NOT NULL DEFAULT 0,
    deleted TINYINT NOT NULL DEFAULT 0,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    KEY idx_event_status_city (status, city),
    KEY idx_event_time (event_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE tb_ticket_sku (
    id BIGINT NOT NULL PRIMARY KEY,
    event_id BIGINT NOT NULL,
    name VARCHAR(64) NOT NULL,
    price BIGINT NOT NULL COMMENT 'cents',
    stock INT NOT NULL DEFAULT 0 COMMENT 'configured capacity',
    sale_start_time DATETIME NOT NULL,
    sale_end_time DATETIME NOT NULL,
    limit_per_user INT NOT NULL DEFAULT 1,
    status TINYINT NOT NULL DEFAULT 0,
    deleted TINYINT NOT NULL DEFAULT 0,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    KEY idx_ticket_sku_event (event_id),
    KEY idx_ticket_sku_status_sale_time (status, sale_start_time, sale_end_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE tb_ticket_order (
    id BIGINT NOT NULL PRIMARY KEY,
    order_no VARCHAR(64) NOT NULL,
    user_id BIGINT NOT NULL,
    event_id BIGINT NOT NULL,
    sku_id BIGINT NOT NULL,
    quantity INT NOT NULL DEFAULT 1,
    total_amount BIGINT NOT NULL COMMENT 'cents',
    status TINYINT NOT NULL DEFAULT 0,
    pay_time DATETIME NULL,
    cancel_time DATETIME NULL,
    active_status TINYINT GENERATED ALWAYS AS (
        CASE WHEN status IN (0, 1) THEN status ELSE NULL END
    ) STORED,
    deleted TINYINT NOT NULL DEFAULT 0,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_ticket_order_no (order_no),
    UNIQUE KEY uk_user_sku_active_status (user_id, sku_id, active_status),
    KEY idx_ticket_order_user_status (user_id, status),
    KEY idx_ticket_order_event_sku_status (event_id, sku_id, status),
    KEY idx_ticket_order_create_time (create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE tb_ticket_order_msg (
    id BIGINT NOT NULL PRIMARY KEY,
    message_id VARCHAR(128) NOT NULL,
    user_id BIGINT NOT NULL,
    event_id BIGINT NOT NULL,
    sku_id BIGINT NOT NULL,
    quantity INT NOT NULL DEFAULT 1,
    status TINYINT NOT NULL DEFAULT 0,
    error_message VARCHAR(1024) NULL,
    deleted TINYINT NOT NULL DEFAULT 0,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_ticket_order_msg_message_id (message_id),
    KEY idx_ticket_order_msg_status_time (status, create_time),
    KEY idx_ticket_order_msg_user_sku (user_id, sku_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE tb_ticket_rollback_task (
    id BIGINT NOT NULL PRIMARY KEY,
    order_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    sku_id BIGINT NOT NULL,
    status TINYINT NOT NULL DEFAULT 0,
    retry_count INT NOT NULL DEFAULT 0,
    error_message VARCHAR(1024) NULL,
    deleted TINYINT NOT NULL DEFAULT 0,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_rollback_task_order (order_id),
    KEY idx_rollback_task_status_time (status, create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE tb_ai_chat_session (
    id BIGINT NOT NULL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    title VARCHAR(128) NOT NULL DEFAULT '',
    deleted TINYINT NOT NULL DEFAULT 0,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    KEY idx_ai_chat_session_user_time (user_id, create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- Retained only for migration-history compatibility; the current runtime has no RAG path.
CREATE TABLE tb_ticket_rule_doc (
    id BIGINT NOT NULL PRIMARY KEY,
    title VARCHAR(128) NOT NULL,
    category VARCHAR(64) NOT NULL,
    content MEDIUMTEXT NOT NULL,
    embedding_doc_id VARCHAR(128) NULL,
    deleted TINYINT NOT NULL DEFAULT 0,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    KEY idx_ticket_rule_doc_category (category),
    KEY idx_ticket_rule_doc_embedding_doc_id (embedding_doc_id),
    FULLTEXT KEY ft_ticket_rule_doc_title_content (title, content)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
