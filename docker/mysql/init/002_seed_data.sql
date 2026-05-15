SET NAMES utf8mb4;

INSERT INTO tb_user (id, phone, nick_name, icon, role, create_time, update_time) VALUES
(1001, '13800000001', '小黄', 'https://example.com/avatar/user-1001.png', 'admin', '2026-05-13 10:00:00', '2026-05-13 10:00:00'),
(1002, '13800000002', '阿陈', 'https://example.com/avatar/user-1002.png', 'user', '2026-05-13 10:05:00', '2026-05-13 10:05:00'),
(1003, '13800000003', '林同学', 'https://example.com/avatar/user-1003.png', 'user', '2026-05-13 10:10:00', '2026-05-13 10:10:00')
ON DUPLICATE KEY UPDATE
    nick_name = VALUES(nick_name),
    icon = VALUES(icon),
    role = VALUES(role),
    update_time = VALUES(update_time);

INSERT INTO tb_event (
    id, title, artist, city, venue, event_time, cover_url, description, status, is_hot, create_time, update_time
) VALUES
(2001, '周杰伦2026世界巡回演唱会上海站', '周杰伦', '上海', '梅赛德斯奔驰文化中心', '2026-07-18 19:30:00', 'https://example.com/covers/jay-shanghai-2026.jpg', '热门巡演上海站，支持多票档抢票。', 1, 1, '2026-05-13 10:20:00', '2026-05-13 10:20:00'),
(2002, '林俊杰JJ20世界巡回演唱会北京站', '林俊杰', '北京', '国家体育馆', '2026-08-08 19:30:00', 'https://example.com/covers/jj-beijing-2026.jpg', '北京站限量开售，实名制入场。', 1, 0, '2026-05-13 10:25:00', '2026-05-13 10:25:00'),
(2003, '五月天回到那一天巡回演唱会广州站', '五月天', '广州', '广州宝能观致文化中心', '2026-09-12 20:00:00', 'https://example.com/covers/mayday-guangzhou-2026.jpg', '广州站预热中，开售前可查看票档。', 0, 0, '2026-05-13 10:30:00', '2026-05-13 10:30:00')
ON DUPLICATE KEY UPDATE
    title = VALUES(title),
    artist = VALUES(artist),
    city = VALUES(city),
    venue = VALUES(venue),
    event_time = VALUES(event_time),
    cover_url = VALUES(cover_url),
    description = VALUES(description),
    status = VALUES(status),
    is_hot = VALUES(is_hot),
    update_time = VALUES(update_time);

INSERT INTO tb_ticket_sku (
    id, event_id, name, price, stock, sale_start_time, sale_end_time, limit_per_user, status, create_time, update_time
) VALUES
(3001, 2001, '看台票 380', 38000, 180, '2026-05-20 12:00:00', '2026-07-18 18:00:00', 1, 1, '2026-05-13 10:40:00', '2026-05-13 10:40:00'),
(3002, 2001, '看台票 580', 58000, 120, '2026-05-20 12:00:00', '2026-07-18 18:00:00', 1, 1, '2026-05-13 10:40:00', '2026-05-13 10:40:00'),
(3003, 2001, '内场票 1280', 128000, 60, '2026-05-20 12:00:00', '2026-07-18 18:00:00', 1, 1, '2026-05-13 10:40:00', '2026-05-13 10:40:00'),
(3004, 2002, '看台票 480', 48000, 150, '2026-06-01 12:00:00', '2026-08-08 18:00:00', 1, 1, '2026-05-13 10:45:00', '2026-05-13 10:45:00'),
(3005, 2002, '内场票 1080', 108000, 80, '2026-06-01 12:00:00', '2026-08-08 18:00:00', 1, 1, '2026-05-13 10:45:00', '2026-05-13 10:45:00'),
(3006, 2003, '预售看台票 580', 58000, 200, '2026-07-01 12:00:00', '2026-09-12 18:00:00', 1, 0, '2026-05-13 10:50:00', '2026-05-13 10:50:00')
ON DUPLICATE KEY UPDATE
    event_id = VALUES(event_id),
    name = VALUES(name),
    price = VALUES(price),
    stock = VALUES(stock),
    sale_start_time = VALUES(sale_start_time),
    sale_end_time = VALUES(sale_end_time),
    limit_per_user = VALUES(limit_per_user),
    status = VALUES(status),
    update_time = VALUES(update_time);

INSERT INTO tb_ticket_order (
    id, order_no, user_id, event_id, sku_id, quantity, total_amount, status, pay_time, cancel_time, create_time, update_time
) VALUES
(4001, 'TR202605130001', 1001, 2001, 3002, 1, 58000, 0, NULL, NULL, '2026-05-13 11:00:00', '2026-05-13 11:00:00'),
(4002, 'TR202605130002', 1002, 2001, 3003, 1, 128000, 1, '2026-05-13 11:08:00', NULL, '2026-05-13 11:05:00', '2026-05-13 11:08:00'),
(4003, 'TR202605130003', 1003, 2002, 3004, 1, 48000, 2, NULL, '2026-05-13 11:16:00', '2026-05-13 11:12:00', '2026-05-13 11:16:00')
ON DUPLICATE KEY UPDATE
    order_no = VALUES(order_no),
    quantity = VALUES(quantity),
    total_amount = VALUES(total_amount),
    status = VALUES(status),
    pay_time = VALUES(pay_time),
    cancel_time = VALUES(cancel_time),
    update_time = VALUES(update_time);

INSERT INTO tb_ticket_order_msg (
    id, message_id, user_id, event_id, sku_id, quantity, status, error_message, create_time, update_time
) VALUES
(5001, 'rush-msg-20260513-0001', 1001, 2001, 3002, 1, 1, NULL, '2026-05-13 11:00:01', '2026-05-13 11:00:03'),
(5002, 'rush-msg-20260513-0002', 1002, 2001, 3003, 1, 1, NULL, '2026-05-13 11:05:01', '2026-05-13 11:05:03'),
(5003, 'rush-msg-20260513-0003', 1003, 2002, 3004, 1, 1, NULL, '2026-05-13 11:12:01', '2026-05-13 11:12:03')
ON DUPLICATE KEY UPDATE
    status = VALUES(status),
    error_message = VALUES(error_message),
    update_time = VALUES(update_time);

INSERT INTO tb_ai_chat_session (id, user_id, title, create_time, update_time) VALUES
(6001, 1001, '帮我抢周杰伦上海站580元票', '2026-05-13 11:20:00', '2026-05-13 11:20:00'),
(6002, 1002, '查询北京演唱会内场票', '2026-05-13 11:22:00', '2026-05-13 11:22:00')
ON DUPLICATE KEY UPDATE
    title = VALUES(title),
    update_time = VALUES(update_time);

INSERT INTO tb_ticket_rule_doc (
    id, title, category, content, embedding_doc_id, create_time, update_time
) VALUES
(7001, '实名制购票规则', '购票规则', '购票时需填写真实观演人信息，入场时票证人信息需一致。', 'rule-real-name-001', '2026-05-13 11:30:00', '2026-05-13 11:30:00'),
(7002, '限购说明', '限购说明', '同一用户对同一票档限购一张，重复抢票请求会被系统拦截。', 'rule-limit-001', '2026-05-13 11:31:00', '2026-05-13 11:31:00'),
(7003, '退票与取消规则', '退票规则', '待支付订单可主动取消，超时未支付订单将自动取消并回滚库存。', 'rule-cancel-001', '2026-05-13 11:32:00', '2026-05-13 11:32:00'),
(7004, '排队抢票规则', '排队规则', '抢票请求通过 Redis Lua 完成库存预扣减，成功后进入异步订单创建队列。', 'rule-queue-001', '2026-05-13 11:33:00', '2026-05-13 11:33:00')
ON DUPLICATE KEY UPDATE
    title = VALUES(title),
    category = VALUES(category),
    content = VALUES(content),
    embedding_doc_id = VALUES(embedding_doc_id),
    update_time = VALUES(update_time);
