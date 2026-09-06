package com.hmdp.utils;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.github.benmanes.caffeine.cache.Cache;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import javax.annotation.PreDestroy;
import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.Random;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.*;

/**
 * 多级缓存服务：Caffeine（一级）→ Redis（二级）→ MySQL（三级）
 *
 * <pre>
 *   命中率递减、速度递减、成本递减
 *   Caffeine 纳秒级（JVM 内存）
 *   → Redis 毫秒级（网络 IO）
 *   → MySQL 毫秒~秒级（磁盘 IO）
 * </pre>
 */
@Slf4j
@Component
public class MultiLevelCacheService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private Cache<String, Object> shopLocalCache;

    /** 逻辑过期异步重建线程池 */
    private final ExecutorService rebuildExecutor = Executors.newFixedThreadPool(10, r -> {
        Thread thread = new Thread(r, "shop-cache-rebuild");
        thread.setDaemon(true);
        return thread;
    });

    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT;

    static {
        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));
        UNLOCK_SCRIPT.setResultType(Long.class);
    }

    /** 随机 TTL 因子，避免缓存雪崩 */
    private static final Random RANDOM = new Random();

    // ==================== 公开 API ====================

    /**
     * 多级缓存查询（穿透保护 + 逻辑过期防击穿）
     *
     * @param keyPrefix Redis key 前缀
     * @param id        业务 ID
     * @param type      返回类型
     * @param dbFallback 查库函数
     * @param ttl       缓存时间
     * @param unit      时间单位
     */
    @SuppressWarnings("unchecked")
    public <R, ID> R queryWithMultiLevel(
            String keyPrefix, ID id, Class<R> type,
            Function<ID, R> dbFallback, Long ttl, TimeUnit unit) {

        String cacheKey = keyPrefix + id;

        // ── 第一层：Caffeine 本地缓存 ──
        R local = (R) shopLocalCache.getIfPresent(cacheKey);
        if (local != null) {
            log.debug("[多级缓存] L1 Caffeine 命中: {}", cacheKey);
            return local;
        }

        // ── 第二层：Redis ──
        String json = stringRedisTemplate.opsForValue().get(cacheKey);
        if (StrUtil.isNotBlank(json)) {
            // 检查逻辑过期
            RedisData redisData = JSONUtil.toBean(json, RedisData.class);
            R data = JSONUtil.toBean((cn.hutool.json.JSONObject) redisData.getData(), type);
            LocalDateTime expireTime = redisData.getExpireTime();

            if (expireTime.isAfter(LocalDateTime.now())) {
                // 未过期 → 写回 Caffeine，返回
                shopLocalCache.put(cacheKey, data);
                log.debug("[多级缓存] L2 Redis 命中: {}", cacheKey);
                return data;
            }

            // 逻辑过期 → 异步重建
            rebuildAsync(keyPrefix, id, dbFallback, ttl, unit);
            // 当前请求可返回旧数据，但不能把过期值重新塞回 L1；重建线程会写入新值。
            return data;
        }

        // 空值防穿透
        if (json != null) {
            return null;
        }

        // ── 第三层：查 MySQL（互斥锁防击穿）──
        R result = queryWithMutexLock(keyPrefix, id, type, dbFallback, ttl, unit);
        if (result != null) {
            shopLocalCache.put(cacheKey, result);
        }
        return result;
    }

    /**
     * 删除所有级别的缓存（写操作时调用）
     */
    public void evict(String keyPrefix, Object id) {
        String key = keyPrefix + id;
        shopLocalCache.invalidate(key);
        stringRedisTemplate.delete(key);
        log.debug("[多级缓存] 已清除: {}", key);
    }

    // ==================== 内部实现 ====================

    /**
     * SETNX 互斥锁查库
     */
    private <R, ID> R queryWithMutexLock(
            String keyPrefix, ID id, Class<R> type,
            Function<ID, R> dbFallback, Long ttl, TimeUnit unit) {

        String key = keyPrefix + id;
        String lockKey = LOCK_SHOP_KEY + id;

        for (int attempt = 0; attempt < 20; attempt++) {
            String token = tryLock(lockKey);
            if (token != null) {
                try {
                    String json = stringRedisTemplate.opsForValue().get(key);
                    if (StrUtil.isNotBlank(json)) {
                        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
                        return JSONUtil.toBean((cn.hutool.json.JSONObject) redisData.getData(), type);
                    }
                    if (json != null) {
                        return null;
                    }
                    R result = dbFallback.apply(id);
                    if (result == null) {
                        long randomTtl = CACHE_NULL_TTL + RANDOM.nextInt(3);
                        stringRedisTemplate.opsForValue().set(key, "", randomTtl, TimeUnit.MINUTES);
                        return null;
                    }
                    writeWithLogicalExpire(key, result, ttl, unit);
                    return result;
                } finally {
                    unlock(lockKey, token);
                }
            }
            try {
                Thread.sleep(25L + RANDOM.nextInt(26));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return dbFallback.apply(id);
            }
            String json = stringRedisTemplate.opsForValue().get(key);
            if (StrUtil.isNotBlank(json)) {
                RedisData redisData = JSONUtil.toBean(json, RedisData.class);
                return JSONUtil.toBean((cn.hutool.json.JSONObject) redisData.getData(), type);
            }
            if (json != null) {
                return null;
            }
        }
        log.warn("缓存互斥锁等待超时，降级查询数据库: {}", key);
        return dbFallback.apply(id);
    }

    /**
     * 异步重建过期缓存
     */
    private <R, ID> void rebuildAsync(
            String keyPrefix, ID id, Function<ID, R> dbFallback, Long ttl, TimeUnit unit) {

        String lockKey = LOCK_SHOP_KEY + id;
        String token = tryLock(lockKey);
        if (token == null) return; // 已经有别的线程在重建

        try {
            rebuildExecutor.submit(() -> {
                try {
                    R data = dbFallback.apply(id);
                    if (data != null) {
                        writeWithLogicalExpire(keyPrefix + id, data, ttl, unit);
                        shopLocalCache.put(keyPrefix + id, data);
                    } else {
                        stringRedisTemplate.opsForValue().set(
                                keyPrefix + id, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                        shopLocalCache.invalidate(keyPrefix + id);
                    }
                } finally {
                    unlock(lockKey, token);
                }
            });
        } catch (RuntimeException rejected) {
            unlock(lockKey, token);
            throw rejected;
        }
    }

    /**
     * 写入 Redis，带逻辑过期时间戳
     */
    private void writeWithLogicalExpire(String key, Object value, Long ttl, TimeUnit unit) {
        RedisData redisData = new RedisData();
        redisData.setData(value);
        // 随机 TTL ± 20%，避免同时过期引发雪崩
        long baseSec = unit.toSeconds(ttl);
        long jitter = (long) (baseSec * 0.2 * RANDOM.nextDouble());
        long logicalTtlSeconds = Math.max(1, baseSec + jitter);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(logicalTtlSeconds));
        // 逻辑过期用于平滑重建，物理过期是故障兜底，避免永久脏数据。
        long physicalTtlSeconds = Math.max(logicalTtlSeconds * 2, logicalTtlSeconds + 3600);
        stringRedisTemplate.opsForValue().set(
                key, JSONUtil.toJsonStr(redisData), physicalTtlSeconds, TimeUnit.SECONDS);
    }

    private String tryLock(String key) {
        String token = UUID.randomUUID().toString();
        Boolean ok = stringRedisTemplate.opsForValue().setIfAbsent(key, token, 10, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(ok) ? token : null;
    }

    private void unlock(String key, String token) {
        stringRedisTemplate.execute(UNLOCK_SCRIPT, Collections.singletonList(key), token);
    }

    @PreDestroy
    public void shutdown() {
        rebuildExecutor.shutdown();
    }
}
