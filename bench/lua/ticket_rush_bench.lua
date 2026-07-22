-- Redis-only benchmark equivalent of the production stock + dedup operations.
-- KEYS[3] supplies a unique synthetic user id per invocation so redis-benchmark does not
-- accidentally measure only the DUPLICATE branch.
local userId = tostring(redis.call('INCR', KEYS[3]))
local stock = tonumber(redis.call('GET', KEYS[1]) or '0')
if stock <= 0 then
    return 1
end
if redis.call('SISMEMBER', KEYS[2], userId) == 1 then
    return 2
end
redis.call('DECR', KEYS[1])
redis.call('SADD', KEYS[2], userId)
return 0
