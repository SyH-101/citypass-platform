-- 名额释放后的 Redis 库存与归属回补。
-- KEYS[1] stock key, KEYS[2] idempotency marker
-- ARGV: markerTtlSeconds, activityPassId, userId, orderId
if redis.call('exists', KEYS[2]) == 1 then
    return 0
end
if redis.call('exists', KEYS[1]) == 0 then
    return -1
end
local activityPassId = ARGV[2]
local userId = ARGV[3]
local orderId = ARGV[4]
local claimKey = 'reservation:claim:' .. activityPassId .. ':' .. userId
if redis.call('get', claimKey) == orderId then
    redis.call('srem', 'reservation:holders:' .. activityPassId, userId)
    redis.call('del', claimKey)
end
redis.call('incrby', KEYS[1], 1)
redis.call('set', KEYS[2], '1', 'EX', ARGV[1])
return 1
