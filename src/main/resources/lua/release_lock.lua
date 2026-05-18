-- Compare-and-delete: only release the lock if the value matches our token.
-- Prevents node A from deleting node B's lock when A's work exceeded the TTL.
-- KEYS[1] = lock key
-- ARGV[1] = expected token
if redis.call('GET', KEYS[1]) == ARGV[1] then
    return redis.call('DEL', KEYS[1])
else
    return 0
end
