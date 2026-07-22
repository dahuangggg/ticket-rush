-- Rush Reservation 原子脚本。
--
-- KEYS[1] = ticket:{skuId}:stock
-- KEYS[2] = ticket:{skuId}:buyers
-- KEYS[3] = ticket:{skuId}:meta
-- KEYS[4] = ticket:{skuId}:reservation:{reservationId}
-- KEYS[5] = ticket:{skuId}:request:{userId}:{idempotencyHash}
-- KEYS[6] = ticket:{skuId}:outbox
--
-- ARGV[1] = userId
-- ARGV[2] = reservationId
-- ARGV[3] = eventId
-- ARGV[4] = skuId
-- ARGV[5] = quantity
--
-- 返回字符串：RESERVED|id / EXISTING|id / SOLD_OUT / DUPLICATE_USER / SKU_UNAVAILABLE

local function keyType(key)
    local reply = redis.call('TYPE', key)
    if type(reply) == 'table' then
        return reply['ok']
    end
    return reply
end

local function hasType(key, expected, allowMissing)
    local actual = keyType(key)
    return actual == expected or (allowMissing and actual == 'none')
end

-- Redis embeds Lua 5.1 numbers as IEEE-754 doubles. Keep every business integer inside the exact
-- integer range, and keep timestamps inside Java LocalDateTime's supported teaching horizon.
local MAX_SAFE_INTEGER = 9007199254740991
local MAX_EPOCH_SECOND = 253402300799 -- 9999-12-31T23:59:59Z

local function unsignedInteger(raw, maximum)
    if not raw or not string.match(raw, '^%d+$') then
        return nil
    end
    local value = tonumber(raw)
    if not value or value < 0 or value > maximum or value ~= math.floor(value) then
        return nil
    end
    return value
end

-- Redis Lua 原子执行但不会在运行时错误时回滚先前写入，因此必须先校验所有 key 类型。
if not hasType(KEYS[1], 'string', true)
        or not hasType(KEYS[2], 'set', true)
        or not hasType(KEYS[3], 'hash', false)
        or not hasType(KEYS[4], 'none', false)
        or not hasType(KEYS[5], 'string', true)
        or not hasType(KEYS[6], 'stream', true) then
    return 'CORRUPT_STATE'
end

local existing = redis.call('GET', KEYS[5])
if existing then
    return 'EXISTING|' .. existing
end

local metaEventId = redis.call('HGET', KEYS[3], 'eventId')
local metaStatus = redis.call('HGET', KEYS[3], 'status')
local saleStartRaw = redis.call('HGET', KEYS[3], 'saleStart')
local saleEndRaw = redis.call('HGET', KEYS[3], 'saleEnd')
local unitPrice = redis.call('HGET', KEYS[3], 'unitPrice')
local cleanupAtRaw = redis.call('HGET', KEYS[3], 'cleanupAt')
local now = tonumber(redis.call('TIME')[1])

if not metaEventId
        or not saleStartRaw
        or not saleEndRaw
        or not unitPrice
        or not cleanupAtRaw
        or metaEventId ~= ARGV[3]
        or metaStatus ~= '1'
        or ARGV[5] ~= '1' then
    return 'SKU_UNAVAILABLE'
end

local saleStart = unsignedInteger(saleStartRaw, MAX_EPOCH_SECOND)
local saleEnd = unsignedInteger(saleEndRaw, MAX_EPOCH_SECOND)
local cleanupAt = unsignedInteger(cleanupAtRaw, MAX_EPOCH_SECOND)
local unitPriceValue = unsignedInteger(unitPrice, MAX_SAFE_INTEGER)
if not saleStart or not saleEnd or not cleanupAt or not unitPriceValue
        or saleStart > saleEnd or cleanupAt < saleEnd then
    return 'CORRUPT_STATE'
end
if now < saleStart or now > saleEnd then
    return 'SKU_UNAVAILABLE'
end

local stockRaw = redis.call('GET', KEYS[1])
if not stockRaw then
    return 'SOLD_OUT'
end
if not string.match(stockRaw, '^%-?%d+$') then
    return 'CORRUPT_STATE'
end
local stock = tonumber(stockRaw)
if not stock or stock < -2147483648 or stock > 2147483647 then
    return 'CORRUPT_STATE'
end
if stock <= 0 then
    return 'SOLD_OUT'
end

if redis.call('SISMEMBER', KEYS[2], ARGV[1]) == 1 then
    return 'DUPLICATE_USER'
end

-- XADD has stream-ID invariants beyond TYPE (for example a corrupt far-future last ID). Keep it as
-- the first mutation so any such Redis error occurs before inventory or identity state is changed.
redis.call('XADD', KEYS[6], '*',
        'reservationId', ARGV[2],
        'userId', ARGV[1],
        'eventId', ARGV[3],
        'skuId', ARGV[4],
        'quantity', ARGV[5],
        'unitPrice', unitPrice,
        'createdAt', tostring(now))
redis.call('DECR', KEYS[1])
redis.call('SADD', KEYS[2], ARGV[1])
redis.call('HSET', KEYS[4],
        'reservationId', ARGV[2],
        'userId', ARGV[1],
        'eventId', ARGV[3],
        'skuId', ARGV[4],
        'quantity', ARGV[5],
        'unitPrice', unitPrice,
        'status', 'RESERVED',
        'createdAt', tostring(now))
redis.call('SET', KEYS[5], ARGV[2])

-- cleanupAt is an earliest-GC business marker, not an automatic TTL. Accepted reservations,
-- deduplication evidence and the relay journal must survive an arbitrarily long downstream outage.
-- A future lifecycle-aware GC may remove only entries proven terminal in the durable ledger.

return 'RESERVED|' .. ARGV[2]
