-- 将已释放订单持有的 Redis 名额原子转给候补订单；库存数量保持不变。
-- KEYS[1] idempotency marker
-- ARGV: markerTtl, activityPassId, oldUserId, oldOrderId, newUserId, newOrderId
if redis.call('exists', KEYS[1]) == 1 then
    return 0
end

local activityPassId = ARGV[2]
local oldUserId = ARGV[3]
local oldOrderId = ARGV[4]
local newUserId = ARGV[5]
local newOrderId = ARGV[6]
local holderKey = 'reservation:holders:' .. activityPassId
local oldClaimKey = 'reservation:claim:' .. activityPassId .. ':' .. oldUserId
local newClaimKey = 'reservation:claim:' .. activityPassId .. ':' .. newUserId

if redis.call('get', oldClaimKey) == oldOrderId then
    redis.call('srem', holderKey, oldUserId)
    redis.call('del', oldClaimKey)
end
redis.call('sadd', holderKey, newUserId)
redis.call('set', newClaimKey, newOrderId)
redis.call('set', KEYS[1], '1', 'EX', ARGV[1])
return 1
