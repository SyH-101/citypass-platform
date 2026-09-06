-- 1.参数列表
-- 1.1.优惠券id
local voucherId = ARGV[1]
-- 1.2.用户id
local userId = ARGV[2]
-- 1.3.订单id
local orderId = ARGV[3]
-- 事务标记 TTL（秒）
local txnTtl = tonumber(ARGV[4])

-- 2.数据key
-- 2.1.库存key
local stockKey = 'seckill:stock:' .. voucherId
-- 2.2.订单key
local orderKey = 'seckill:order:' .. voucherId
local claimKey = 'seckill:claim:' .. voucherId .. ':' .. userId

-- 3.脚本业务
-- 3.1.判断库存是否充足 get stockKey（key 不存在视为无库存，避免 tonumber(nil) 报错）
local stock = tonumber(redis.call('get', stockKey))
if stock == nil or stock <= 0 then
    -- 3.2.库存不足，返回1
    return 1
end
-- 3.2.判断用户是否下单 SISMEMBER orderKey userId
if(redis.call('sismember', orderKey, userId) == 1) then
    -- 3.3.存在，说明是重复下单，返回2
    return 2
end
-- 3.4.扣库存 incrby stockKey -1
redis.call('incrby', stockKey, -1)
-- 3.5.下单（保存用户）sadd orderKey userId
redis.call('sadd', orderKey, userId)
-- 3.6.记录本次预扣归属。claim 不设 TTL，直到订单落库或对账完成，避免事务回查误回滚
redis.call('set', claimKey, orderId)
-- 3.7.短期事务标记用于快速回查；过期后仍可用 claimKey 确认
redis.call('set', 'seckill:txn:' .. orderId, '1', 'EX', txnTtl)
-- 3.8.返回0：校验通过，由事务消息 COMMIT 后投递，异步落库
return 0
