-- 将已释放订单持有的 Redis 名额原子转给候补订单；库存数量保持不变。
-- KEYS[1] permanent idempotency marker
-- ARGV: activityPassId, oldUserId, oldOrderId, oldVersion, newUserId, newOrderId, newVersion
if redis.call('exists', KEYS[1]) == 1 then
    return 0
end

local activityPassId = ARGV[1]
local oldUserId = ARGV[2]
local oldOrderId = ARGV[3]
local oldVersion = ARGV[4]
local newUserId = ARGV[5]
local newOrderId = ARGV[6]
local newVersion = ARGV[7]
local holderKey = 'reservation:holders:' .. activityPassId
local oldClaimKey = 'reservation:claim:' .. activityPassId .. ':' .. oldUserId
local newClaimKey = 'reservation:claim:' .. activityPassId .. ':' .. newUserId

local oldClaim = redis.call('get', oldClaimKey)
local newClaim = redis.call('get', newClaimKey)
local expectedOld = oldOrderId .. ':' .. oldVersion
local expectedNew = newOrderId .. ':' .. newVersion

-- 不允许覆盖新用户的其他有效归属。
if newClaim and newClaim ~= expectedNew then
    return -2
end

-- 兼容 v3 的无版本直接 claim，其他不匹配一律拒绝交接。
if oldClaim ~= expectedOld and not (oldVersion == '0' and oldClaim == oldOrderId) then
    return -1
end

redis.call('srem', holderKey, oldUserId)
redis.call('del', oldClaimKey)
redis.call('sadd', holderKey, newUserId)
redis.call('set', newClaimKey, expectedNew)
redis.call('set', KEYS[1], '1')
return 1
