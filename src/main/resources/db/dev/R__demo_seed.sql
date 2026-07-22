SET NAMES utf8mb4;

INSERT INTO tb_user (id, phone, nick_name, icon, role) VALUES
(1001, '13800000001', '教学管理员', '', 'admin'),
(1002, '13800000002', '演示用户', '', 'user')
ON DUPLICATE KEY UPDATE nick_name = VALUES(nick_name), role = VALUES(role);

INSERT INTO tb_event (
    id, title, artist, city, venue, event_time, cover_url, description, status, is_hot
) VALUES
(2001, '周杰伦世界巡回演唱会上海站', '周杰伦', '上海', '梅赛德斯奔驰文化中心',
 DATE_ADD(NOW(), INTERVAL 30 DAY), '', '教学用热点活动，时间相对当前日期生成。', 1, 1),
(2002, '林俊杰世界巡回演唱会北京站', '林俊杰', '北京', '国家体育馆',
 DATE_ADD(NOW(), INTERVAL 45 DAY), '', '教学用普通活动。', 1, 0)
ON DUPLICATE KEY UPDATE
    event_time = VALUES(event_time), status = VALUES(status), is_hot = VALUES(is_hot);

INSERT INTO tb_ticket_sku (
    id, event_id, name, price, stock, sale_start_time, sale_end_time, limit_per_user, status
) VALUES
(3001, 2001, '看台票 380', 38000, 180, DATE_SUB(NOW(), INTERVAL 1 DAY), DATE_ADD(NOW(), INTERVAL 20 DAY), 1, 1),
(3002, 2001, '看台票 580', 58000, 120, DATE_SUB(NOW(), INTERVAL 1 DAY), DATE_ADD(NOW(), INTERVAL 20 DAY), 1, 1),
(3003, 2001, '内场票 1280', 128000, 60, DATE_SUB(NOW(), INTERVAL 1 DAY), DATE_ADD(NOW(), INTERVAL 20 DAY), 1, 1),
(3004, 2002, '看台票 480', 48000, 150, DATE_ADD(NOW(), INTERVAL 5 DAY), DATE_ADD(NOW(), INTERVAL 35 DAY), 1, 0)
ON DUPLICATE KEY UPDATE
    event_id = VALUES(event_id), price = VALUES(price), stock = VALUES(stock),
    sale_start_time = VALUES(sale_start_time), sale_end_time = VALUES(sale_end_time), status = VALUES(status);
