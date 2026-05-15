-- 抢票回滚脚本：归还库存 + 移除用户去重记录
--
-- KEYS[1] = ticket:stock:{skuId}       库存计数器
-- KEYS[2] = ticket:order:user:{skuId}  用户去重集合
-- ARGV[1] = userId（字符串）
--
-- 在以下场景调用：
--   1. Kafka 发送失败后立即回滚
--   2. 用户取消订单
--   3. 订单超时未支付
--   4. 补偿任务重试
--
-- 返回值：0 = 回滚完成

redis.call('INCR', KEYS[1])
redis.call('SREM', KEYS[2], ARGV[1])
return 0  -- ROLLBACK_OK
