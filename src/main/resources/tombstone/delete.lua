-- Versioned delete: remove the cached value and leave a tombstone holding the version the row had
-- when it was deleted (the delete does not change the version). Runs atomically inside Valkey.
-- KEYS[1] = value hash   KEYS[2] = tombstone   ARGV[1] = version at delete   ARGV[2] = tombstone TTL (seconds)
local v = tonumber(ARGV[1])
redis.call('DEL', KEYS[1])

-- Keep the highest delete version if a tombstone is already there (e.g. a repeated delete).
local dead = redis.call('GET', KEYS[2])
if (not dead) or v > tonumber(dead) then
  redis.call('SET', KEYS[2], ARGV[1], 'EX', tonumber(ARGV[2]))
end
return 'DELETED'
