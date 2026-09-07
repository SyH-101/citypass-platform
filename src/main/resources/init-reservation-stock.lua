-- 发布限量活动后初始化 Redis 库存。marker 保证任务重试不会覆盖已经被扣减的库存。
-- KEYS[1] stock key, KEYS[2] initialization marker; ARGV[1] initial stock
if redis.call('exists', KEYS[2]) == 1 then
    return 0
end
redis.call('set', KEYS[1], ARGV[1])
redis.call('set', KEYS[2], '1')
return 1
