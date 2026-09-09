-- 名额释放后的 Redis 库存与归属回补。
-- KEYS[1] stock key, KEYS[2] permanent idempotency marker
-- ARGV: activityPassId, userId, orderId, resourceVersion
if redis.call('exists', KEYS[2]) == 1 then
    return 0
end
if redis.call('exists', KEYS[1]) == 0 then
    return -1
end
local activityPassId = ARGV[1]
local userId = ARGV[2]
local orderId = ARGV[3]
local resourceVersion = ARGV[4]
local claimKey = 'reservation:claim:' .. activityPassId .. ':' .. userId
local existing = redis.call('get', claimKey)
local expectedClaim = orderId .. ':' .. resourceVersion
if existing ~= expectedClaim and not (resourceVersion == '0' and existing == orderId) then
    return -2
end
redis.call('srem', 'reservation:holders:' .. activityPassId, userId)
redis.call('del', claimKey)
redis.call('incrby', KEYS[1], 1)
redis.call('set', KEYS[2], '1')
return 1
