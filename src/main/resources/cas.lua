-- Version-guarded check-and-set, executed atomically inside Valkey.
-- KEYS[1] = entry key   ARGV[1] = payload   ARGV[2] = version   ARGV[3] = ttl (seconds)
local cur = tonumber(redis.call('HGET', KEYS[1], 'ver') or '-1')
if tonumber(ARGV[2]) > cur then
  redis.call('HSET', KEYS[1], 'payload', ARGV[1], 'ver', ARGV[2])
  redis.call('EXPIRE', KEYS[1], tonumber(ARGV[3]))
  return 'UPDATED'
end
return 'STALE'
