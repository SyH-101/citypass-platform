-- Per-user per-story version fences late events. Replaying ZADD never increments a counter.
local current = tonumber(redis.call('HGET', KEYS[2], ARGV[1]) or '-1')
local version = tonumber(ARGV[2])
if version < current then return 0 end
redis.call('HSET', KEYS[2], ARGV[1], version)
if ARGV[3] == 'PUBLISHED' then
    redis.call('ZADD', KEYS[1], ARGV[4], ARGV[1])
else
    redis.call('ZREM', KEYS[1], ARGV[1])
end
return 1
