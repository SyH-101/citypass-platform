-- 仅允许拥有 claim 的订单回滚 Redis 预扣，避免一次失败消息破坏另一笔成功订单
-- ARGV: voucherId, userId, orderId
local voucherId = ARGV[1]
local userId = ARGV[2]
local orderId = ARGV[3]
local stockKey = 'seckill:stock:' .. voucherId
local orderKey = 'seckill:order:' .. voucherId
local claimKey = 'seckill:claim:' .. voucherId .. ':' .. userId

if redis.call('get', claimKey) ~= orderId then
    return 0
end
if redis.call('exists', stockKey) == 0 then
    return -1
end
redis.call('incrby', stockKey, 1)
redis.call('srem', orderKey, userId)
redis.call('del', claimKey)
redis.call('del', 'seckill:txn:' .. orderId)
return 1
