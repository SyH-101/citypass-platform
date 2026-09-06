-- 超时关单后的 Redis 库存回补。marker 让“执行成功但任务状态未落库”的重试不会重复 INCR。
-- KEYS[1] stock key, KEYS[2] idempotency marker
-- ARGV[1] marker TTL seconds
if redis.call('exists', KEYS[2]) == 1 then
    return 0
end
if redis.call('exists', KEYS[1]) == 0 then
    return -1
end
redis.call('incrby', KEYS[1], 1)
redis.call('set', KEYS[2], '1', 'EX', ARGV[1])
return 1
