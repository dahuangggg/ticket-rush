-- Atomically preserve a malformed Reservation Journal entry outside the active relay stream.
-- Both keys use the same ticket:{skuId} Redis Cluster hash tag.
--
-- KEYS[1] = ticket:{skuId}:outbox
-- KEYS[2] = ticket:{skuId}:outbox:quarantine
-- ARGV[1] = source stream ID
-- ARGV[2] = parse failure class/reason
--
-- Return: 1 moved, 0 source already absent, -1 corrupt key type.

local function keyType(key)
    local reply = redis.call('TYPE', key)
    if type(reply) == 'table' then
        return reply['ok']
    end
    return reply
end

local sourceType = keyType(KEYS[1])
local quarantineType = keyType(KEYS[2])
if (sourceType ~= 'stream' and sourceType ~= 'none')
        or (quarantineType ~= 'stream' and quarantineType ~= 'none') then
    return -1
end

if sourceType == 'none' then
    return 0
end

local records = redis.call('XRANGE', KEYS[1], ARGV[1], ARGV[1], 'COUNT', 1)
if #records == 0 then
    return 0
end

-- cmsgpack preserves the original ordered field/value array as one binary evidence value and avoids
-- Lua unpack/argument-count limits for malformed entries with many fields.
local rawFields = cmsgpack.pack(records[1][2])
local quarantinedAt = tostring(redis.call('TIME')[1])

-- XADD is the first write. If the quarantine stream itself is corrupt or exhausted, the source
-- entry remains untouched and the relay retries rather than losing forensic evidence.
redis.call('XADD', KEYS[2], '*',
        'sourceStreamId', ARGV[1],
        'quarantinedAt', quarantinedAt,
        'reason', ARGV[2],
        'rawFieldsCmsgpack', rawFields)
redis.call('XDEL', KEYS[1], ARGV[1])

-- Do not inherit a legacy TTL from the active stream. Quarantine is evidence and remains until a
-- lifecycle-aware GC proves the corresponding Reservation terminal in the durable ledger.

return 1
