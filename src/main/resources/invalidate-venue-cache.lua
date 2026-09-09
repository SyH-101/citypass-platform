-- KEYS[1] main cache, KEYS[2] baseline cache, KEYS[3] version key
-- ARGV[1] pub/sub channel, ARGV[2] venueId, ARGV[3] committed database version
local current = tonumber(redis.call('get', KEYS[3]) or '0')
local requested = tonumber(ARGV[3])
if requested < 0 then
    requested = current + 1
end
local version = requested
if current > requested then
    version = current
end
redis.call('set', KEYS[3], version)
redis.call('del', KEYS[1])
redis.call('del', KEYS[2])
redis.call('publish', ARGV[1], ARGV[2] .. ':' .. version)
return version
