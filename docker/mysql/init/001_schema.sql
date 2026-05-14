SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS tb_user (
    id BIGINT NOT NULL PRIMARY KEY COMMENT 'User id',
    phone VARCHAR(20) NOT NULL COMMENT 'Login phone number',
    nick_name VARCHAR(64) NOT NULL DEFAULT '' COMMENT 'Display name',
    icon VARCHAR(512) NOT NULL DEFAULT '' COMMENT 'Avatar URL',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT 'Logical delete flag: 0 normal, 1 deleted',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Create time',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'Update time',
    UNIQUE KEY uk_user_phone (phone),
    KEY idx_user_create_time (create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='User account';

CREATE TABLE IF NOT EXISTS tb_event (
    id BIGINT NOT NULL PRIMARY KEY COMMENT 'Event id',
    title VARCHAR(200) NOT NULL COMMENT 'Event title',
    artist VARCHAR(100) NOT NULL COMMENT 'Artist name',
    city VARCHAR(50) NOT NULL COMMENT 'Event city',
    venue VARCHAR(200) NOT NULL COMMENT 'Event venue',
    event_time DATETIME NOT NULL COMMENT 'Event start time',
    cover_url VARCHAR(500) NOT NULL DEFAULT '' COMMENT 'Cover image URL',
    description TEXT NULL COMMENT 'Event description',
    status TINYINT NOT NULL DEFAULT 0 COMMENT 'Status: 0 unpublished, 1 on sale, 2 ended',
    is_hot TINYINT NOT NULL DEFAULT 0 COMMENT 'Hot flag: 0 normal, 1 hot',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT 'Logical delete flag: 0 normal, 1 deleted',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Create time',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'Update time',
    KEY idx_event_status_city (status, city),
    KEY idx_event_time (event_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='Event';

CREATE TABLE IF NOT EXISTS tb_ticket_sku (
    id BIGINT NOT NULL PRIMARY KEY COMMENT 'Ticket SKU id',
    event_id BIGINT NOT NULL COMMENT 'Event id',
    name VARCHAR(64) NOT NULL COMMENT 'SKU name, such as stand ticket or VIP ticket',
    price BIGINT NOT NULL COMMENT 'Ticket price in cents',
    stock INT NOT NULL DEFAULT 0 COMMENT 'Database stock',
    sale_start_time DATETIME NOT NULL COMMENT 'Sale start time',
    sale_end_time DATETIME NOT NULL COMMENT 'Sale end time',
    limit_per_user INT NOT NULL DEFAULT 1 COMMENT 'Per-user purchase limit',
    status TINYINT NOT NULL DEFAULT 0 COMMENT 'Status: 0 not started, 1 on sale, 2 sold out',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT 'Logical delete flag: 0 normal, 1 deleted',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Create time',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'Update time',
    KEY idx_ticket_sku_event (event_id),
    KEY idx_ticket_sku_status_sale_time (status, sale_start_time, sale_end_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='Ticket SKU';

CREATE TABLE IF NOT EXISTS tb_ticket_order (
    id BIGINT NOT NULL PRIMARY KEY COMMENT 'Order id',
    order_no VARCHAR(64) NOT NULL COMMENT 'Business order number',
    user_id BIGINT NOT NULL COMMENT 'User id',
    event_id BIGINT NOT NULL COMMENT 'Event id',
    sku_id BIGINT NOT NULL COMMENT 'Ticket SKU id',
    quantity INT NOT NULL DEFAULT 1 COMMENT 'Purchase quantity',
    total_amount BIGINT NOT NULL COMMENT 'Total amount in cents',
    status TINYINT NOT NULL DEFAULT 0 COMMENT 'Status: 0 pending payment, 1 paid, 2 canceled, 3 timeout',
    pay_time DATETIME NULL COMMENT 'Payment time',
    cancel_time DATETIME NULL COMMENT 'Cancel time',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT 'Logical delete flag: 0 normal, 1 deleted',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Create time',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'Update time',
    UNIQUE KEY uk_ticket_order_no (order_no),
    UNIQUE KEY uk_user_sku (user_id, sku_id),
    KEY idx_ticket_order_user_status (user_id, status),
    KEY idx_ticket_order_event_sku_status (event_id, sku_id, status),
    KEY idx_ticket_order_create_time (create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='Ticket order';

CREATE TABLE IF NOT EXISTS tb_ticket_order_msg (
    id BIGINT NOT NULL PRIMARY KEY COMMENT 'Message row id',
    message_id VARCHAR(128) NOT NULL COMMENT 'MQ message id',
    user_id BIGINT NOT NULL COMMENT 'User id',
    event_id BIGINT NOT NULL COMMENT 'Event id',
    sku_id BIGINT NOT NULL COMMENT 'Ticket SKU id',
    quantity INT NOT NULL DEFAULT 1 COMMENT 'Purchase quantity',
    status TINYINT NOT NULL DEFAULT 0 COMMENT 'Status: 0 pending, 1 success, 2 failed',
    error_message VARCHAR(1024) NULL COMMENT 'Failure reason',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT 'Logical delete flag: 0 normal, 1 deleted',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Create time',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'Update time',
    UNIQUE KEY uk_ticket_order_msg_message_id (message_id),
    KEY idx_ticket_order_msg_status_time (status, create_time),
    KEY idx_ticket_order_msg_user_sku (user_id, sku_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='Ticket order MQ message tracking';

CREATE TABLE IF NOT EXISTS tb_ai_chat_session (
    id BIGINT NOT NULL PRIMARY KEY COMMENT 'AI chat session id',
    user_id BIGINT NOT NULL COMMENT 'User id',
    title VARCHAR(128) NOT NULL DEFAULT '' COMMENT 'Session title',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT 'Logical delete flag: 0 normal, 1 deleted',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Create time',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'Update time',
    KEY idx_ai_chat_session_user_time (user_id, create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='AI chat session';

CREATE TABLE IF NOT EXISTS tb_ticket_rule_doc (
    id BIGINT NOT NULL PRIMARY KEY COMMENT 'Rule document id',
    title VARCHAR(128) NOT NULL COMMENT 'Document title',
    category VARCHAR(64) NOT NULL COMMENT 'Rule category',
    content MEDIUMTEXT NOT NULL COMMENT 'Rule content',
    embedding_doc_id VARCHAR(128) NULL COMMENT 'External vector document id',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT 'Logical delete flag: 0 normal, 1 deleted',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Create time',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'Update time',
    KEY idx_ticket_rule_doc_category (category),
    KEY idx_ticket_rule_doc_embedding_doc_id (embedding_doc_id),
    FULLTEXT KEY ft_ticket_rule_doc_title_content (title, content)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='Ticket rule document metadata';
