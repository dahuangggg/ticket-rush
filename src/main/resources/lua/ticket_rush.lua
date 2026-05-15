-- 抢票原子脚本：检查库存 + 用户去重 + 扣减库存 + 记录用户
--
-- KEYS[1] = ticket:stock:{skuId}   库存计数器
-- KEYS[2] = ticket:order:user:{skuId}  用户去重集合
-- ARGV[1] = userId（字符串）
-- ARGV[2] = 去重集合过期时间（秒），作为兜底 TTL 防止回滚失败时用户被永久锁定
--
-- 返回值：
--   0 = SUCCESS  扣减成功，消息已入队
--   1 = SOLD_OUT 库存不足
--   2 = DUPLICATE 用户已抢购过该票档

local stock = tonumber(redis.call('GET', KEYS[1]) or '0')
if stock <= 0 then
    return 1  -- SOLD_OUT
end

if redis.call('SISMEMBER', KEYS[2], ARGV[1]) == 1 then
    return 2  -- DUPLICATE
end

redis.call('DECR', KEYS[1])
redis.call('SADD', KEYS[2], ARGV[1])

-- 设置兜底 TTL：正常流程中用户记录由 rollback 脚本清理，
-- 但若回滚失败且补偿任务也失败，此 TTL 确保记录最终过期，用户不会被永久锁定。
-- 每次 SADD 后刷新 TTL，确保最后一个用户加入后仍有完整过期窗口。
local ttl = tonumber(ARGV[2] or '3600')
if ttl > 0 then
    redis.call('EXPIRE', KEYS[2], ttl)
end

return 0  -- SUCCESS
