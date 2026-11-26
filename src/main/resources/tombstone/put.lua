-- Version-guarded cache write that also respects a delete tombstone. Runs atomically inside Valkey.
-- KEYS[1] = value hash (fields payload, ver)   KEYS[2] = tombstone (the version the row had when it was deleted)
-- ARGV[1] = payload (JSON)   ARGV[2] = version   ARGV[3] = value TTL (seconds)
local v = tonumber(ARGV[2])

-- A delete at version d blocks every write up to and including d: the deleted row's own last version too.
local dead = redis.call('GET', KEYS[2])
if dead and v <= tonumber(dead) then return 'DELETED' end

-- Only an OLDER version is stale. The same version is the same row, so a refill after an expiry is allowed.
local cur = redis.call('HGET', KEYS[1], 'ver')
if cur and v < tonumber(cur) then return 'STALE' end

redis.call('HSET', KEYS[1], 'payload', ARGV[1], 'ver', ARGV[2])
redis.call('EXPIRE', KEYS[1], tonumber(ARGV[3]))
return 'UPDATED'
