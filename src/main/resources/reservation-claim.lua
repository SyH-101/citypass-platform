-- 预约消费者：Redis 库存 + 一人一单（幂等：已 claim 则返回 0，便于 MQ 重试续跑落库）。
-- ARGV: activityPassId, userId, orderId
-- 返回: 0 可落库；1 库存不足；2 该用户已被另一订单 claim
local activityPassId = ARGV[1]
local userId = ARGV[2]
local orderId = ARGV[3]

local stockKey = 'reservation:stock:' .. activityPassId
local holderKey = 'reservation:holders:' .. activityPassId
local claimKey = 'reservation:claim:' .. activityPassId .. ':' .. userId

-- 已 claim：说明上次可能扣了 Redis 但 SQL 未成功，允许继续落库
if redis.call('sismember', holderKey, userId) == 1 then
    if redis.call('get', claimKey) == orderId then
        return 0
    end
    return 2
end

local stock = tonumber(redis.call('get', stockKey))
if stock == nil or stock <= 0 then
    return 1
end

redis.call('incrby', stockKey, -1)
redis.call('sadd', holderKey, userId)
redis.call('set', claimKey, orderId)
return 0
