-- 滑动窗口限流：ZSET 存请求时间戳，窗口外淘汰后计数
-- KEYS[1] = rate:sw:{biz}:{userId}
-- ARGV[1] = windowMs
-- ARGV[2] = maxRequests
-- ARGV[3] = member（唯一请求标识）
-- 返回：1 放行；0 限流
local key = KEYS[1]
local redisTime = redis.call('TIME')
local now = tonumber(redisTime[1]) * 1000 + math.floor(tonumber(redisTime[2]) / 1000)
local window = tonumber(ARGV[1])
local maxReq = tonumber(ARGV[2])
local member = ARGV[3]
local minScore = now - window

redis.call('ZREMRANGEBYSCORE', key, 0, minScore)
local count = redis.call('ZCARD', key)
if count >= maxReq then
    return 0
end
redis.call('ZADD', key, now, member)
redis.call('PEXPIRE', key, window)
return 1
