-- DB 已无库存时清理本次 Redis 占位，并把 Redis 库存收敛为 0，避免继续制造无效请求。
-- ARGV: activityPassId, userId, orderId
local activityPassId = ARGV[1]
local userId = ARGV[2]
local orderId = ARGV[3]
local stockKey = 'reservation:stock:' .. activityPassId
local holderKey = 'reservation:holders:' .. activityPassId
local claimKey = 'reservation:claim:' .. activityPassId .. ':' .. userId

if redis.call('get', claimKey) ~= orderId then
    return 0
end
redis.call('srem', holderKey, userId)
redis.call('del', claimKey)
redis.call('set', stockKey, 0)
return 1
