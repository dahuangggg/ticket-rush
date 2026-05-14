-- KEYS[1] = ticket:stock:{skuId}
-- KEYS[2] = ticket:order:user:{skuId}
-- ARGV[1] = userId（字符串）

local stock = tonumber(redis.call('GET', KEYS[1]) or '0')
if stock <= 0 then
    return 1
end

if redis.call('SISMEMBER', KEYS[2], ARGV[1]) == 1 then
    return 2
end

redis.call('DECR', KEYS[1])
redis.call('SADD', KEYS[2], ARGV[1])
return 0
