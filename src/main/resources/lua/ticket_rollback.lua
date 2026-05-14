-- KEYS[1] = ticket:stock:{skuId}
-- KEYS[2] = ticket:order:user:{skuId}
-- ARGV[1] = userId（字符串）
-- 原子回滚：归还库存 + 移除用户抢购记录

redis.call('INCR', KEYS[1])
redis.call('SREM', KEYS[2], ARGV[1])
return 0
