-- Reservation 状态条件转换。
-- KEYS[1] = reservation hash
-- ARGV[1] = 逗号分隔的允许来源状态
-- ARGV[2] = 目标状态
-- ARGV[3] = 可选附加字段名
-- ARGV[4] = 可选附加字段值
-- 0 = 已转换, 1 = 已是目标状态, 2 = 不存在, 3 = 非法转换

local current = redis.call('HGET', KEYS[1], 'status')
if not current then
    return 2
end
if current == ARGV[2] then
    return 1
end
if not string.find(',' .. ARGV[1] .. ',', ',' .. current .. ',', 1, true) then
    return 3
end

redis.call('HSET', KEYS[1], 'status', ARGV[2])
if ARGV[3] and ARGV[3] ~= '' then
    redis.call('HSET', KEYS[1], ARGV[3], ARGV[4])
end
return 0
