-- KEYS[1] cache data, KEYS[2] version key; ARGV[1] expectedVersion, ARGV[2] json, ARGV[3] ttlSeconds
local current = tonumber(redis.call('get', KEYS[2]) or '0')
local expected = tonumber(ARGV[1])
if expected < current then
    return 0
end
redis.call('set', KEYS[2], expected)
redis.call('set', KEYS[1], ARGV[2], 'EX', ARGV[3])
return 1
