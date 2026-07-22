-- Reservation 的幂等释放脚本。
--
-- KEYS[1] = ticket:{skuId}:stock
-- KEYS[2] = ticket:{skuId}:buyers
-- KEYS[3] = ticket:{skuId}:reservation:{reservationId}
-- ARGV[1] = userId
--
-- 0 = 本次完成释放
-- 1 = 已经释放，无副作用
-- 2 = Reservation 不存在
-- 3 = 已支付，不允许释放
-- 4 = 库存键不存在，拒绝用 INCR 猜测库存
-- 5 = Redis 状态损坏，拒绝部分释放

local function keyType(key)
    local reply = redis.call('TYPE', key)
    if type(reply) == 'table' then
        return reply['ok']
    end
    return reply
end

local reservationType = keyType(KEYS[3])
if reservationType == 'none' then
    return 2
end
if reservationType ~= 'hash' then
    return 5
end

local status = redis.call('HGET', KEYS[3], 'status')
if not status then
    return 2
end
if status == 'RELEASED' then
    return 1
end
if status == 'PAID' then
    return 3
end
if status ~= 'RESERVED'
        and status ~= 'QUEUED'
        and status ~= 'ORDER_CREATED'
        and status ~= 'REJECTED'
        and status ~= 'RELEASE_PENDING' then
    return 5
end

local stockType = keyType(KEYS[1])
if stockType == 'none' then
    return 4
end
if stockType ~= 'string' or keyType(KEYS[2]) ~= 'set' then
    return 5
end

local stockRaw = redis.call('GET', KEYS[1])
if not stockRaw or not string.match(stockRaw, '^%-?%d+$') then
    return 5
end
local stock = tonumber(stockRaw)
if not stock or stock < -2147483648 or stock >= 2147483647 then
    return 5
end
if redis.call('HGET', KEYS[3], 'userId') ~= ARGV[1]
        or redis.call('SISMEMBER', KEYS[2], ARGV[1]) ~= 1 then
    return 5
end

local now = tostring(redis.call('TIME')[1])
redis.call('HSET', KEYS[3], 'status', 'RELEASED', 'releasedAt', now)
redis.call('SREM', KEYS[2], ARGV[1])
redis.call('INCR', KEYS[1])
return 0
