package com.citypass.utils;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.citypass.entity.Venue;
import com.github.benmanes.caffeine.cache.Cache;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import javax.annotation.PreDestroy;
import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.citypass.utils.RedisConstants.CACHE_NULL_TTL;
import static com.citypass.utils.RedisConstants.CACHE_VENUE_VERSION_KEY;
import static com.citypass.utils.RedisConstants.LOCK_VENUE_KEY;

/** Caffeine -> Redis -> MySQL cache with version-checked asynchronous rebuilds. */
@Slf4j
@Component
public class MultiLevelCacheService {

    @Data
    @AllArgsConstructor
    private static class LocalEntry {
        private Object data;
        private long version;
    }

    @Resource private StringRedisTemplate stringRedisTemplate;
    @Resource private Cache<String, Object> venueLocalCache;

    private final ExecutorService rebuildExecutor = Executors.newFixedThreadPool(10, r -> {
        Thread thread = new Thread(r, "venue-cache-rebuild");
        thread.setDaemon(true);
        return thread;
    });

    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT = new DefaultRedisScript<>();
    private static final DefaultRedisScript<Long> WRITE_IF_VERSION_SCRIPT = new DefaultRedisScript<>();
    private static final Random RANDOM = new Random();

    static {
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));
        UNLOCK_SCRIPT.setResultType(Long.class);
        WRITE_IF_VERSION_SCRIPT.setLocation(new ClassPathResource("write-cache-if-version.lua"));
        WRITE_IF_VERSION_SCRIPT.setResultType(Long.class);
    }

    @SuppressWarnings("unchecked")
    public <R, ID> R queryWithMultiLevel(String keyPrefix, ID id, Class<R> type,
                                         Function<ID, R> dbFallback, Long ttl, TimeUnit unit) {
        String cacheKey = keyPrefix + id;
        Object cached = venueLocalCache.getIfPresent(cacheKey);
        if (cached instanceof LocalEntry) {
            log.debug("[多级缓存] L1 Caffeine 命中: {}", cacheKey);
            return (R) ((LocalEntry) cached).getData();
        }

        RedisData redisData = readRedis(cacheKey);
        if (redisData != null) {
            if (Boolean.TRUE.equals(redisData.getNullValue())) return null;
            R data = convert(redisData, type);
            long version = redisData.getVersion() == null ? 0L : redisData.getVersion();
            if (redisData.getExpireTime() != null && redisData.getExpireTime().isAfter(LocalDateTime.now())) {
                venueLocalCache.put(cacheKey, new LocalEntry(data, version));
                log.debug("[多级缓存] L2 Redis 命中: {}", cacheKey);
                return data;
            }
            rebuildAsync(keyPrefix, id, dbFallback, ttl, unit);
            return data;
        }

        R result = queryWithMutexLock(keyPrefix, id, type, dbFallback, ttl, unit);
        if (result != null) venueLocalCache.put(cacheKey, new LocalEntry(result, versionOf(result)));
        return result;
    }

    public void evictLocal(String keyPrefix, Object id) {
        venueLocalCache.invalidate(keyPrefix + id);
    }

    /** Compatibility helper for callers that own no versioned invalidation workflow. */
    public void evict(String keyPrefix, Object id) {
        evictLocal(keyPrefix, id);
        stringRedisTemplate.delete(keyPrefix + id);
    }

    private <R, ID> R queryWithMutexLock(String keyPrefix, ID id, Class<R> type,
                                         Function<ID, R> dbFallback, Long ttl, TimeUnit unit) {
        String key = keyPrefix + id;
        String lockKey = LOCK_VENUE_KEY + id;
        for (int attempt = 0; attempt < 20; attempt++) {
            String token = tryLock(lockKey);
            if (token != null) {
                try {
                    RedisData doubleChecked = readRedis(key);
                    if (doubleChecked != null) {
                        if (Boolean.TRUE.equals(doubleChecked.getNullValue())) return null;
                        return convert(doubleChecked, type);
                    }
                    R result = dbFallback.apply(id);
                    if (result == null) {
                        // No create flow exists for venues; a short null marker is sufficient for penetration control.
                        RedisData nullData = new RedisData();
                        nullData.setNullValue(true);
                        nullData.setVersion(currentVersion(id));
                        nullData.setExpireTime(LocalDateTime.now().plusMinutes(CACHE_NULL_TTL));
                        stringRedisTemplate.opsForValue().set(
                                key, JSONUtil.toJsonStr(nullData), CACHE_NULL_TTL, TimeUnit.MINUTES);
                        return null;
                    }
                    writeWithLogicalExpire(key, id, result, ttl, unit);
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
            RedisData refreshed = readRedis(key);
            if (refreshed != null) {
                if (Boolean.TRUE.equals(refreshed.getNullValue())) return null;
                return convert(refreshed, type);
            }
        }
        log.warn("缓存互斥锁等待超时，降级查询数据库: {}", key);
        return dbFallback.apply(id);
    }

    private <R, ID> void rebuildAsync(String keyPrefix, ID id, Function<ID, R> dbFallback,
                                      Long ttl, TimeUnit unit) {
        String lockKey = LOCK_VENUE_KEY + id;
        String token = tryLock(lockKey);
        if (token == null) return;
        try {
            rebuildExecutor.submit(() -> {
                try {
                    R data = dbFallback.apply(id);
                    if (data != null && writeWithLogicalExpire(keyPrefix + id, id, data, ttl, unit)) {
                        venueLocalCache.put(keyPrefix + id, new LocalEntry(data, versionOf(data)));
                    } else {
                        venueLocalCache.invalidate(keyPrefix + id);
                    }
                } catch (RuntimeException e) {
                    log.error("异步重建场馆缓存失败: {}", id, e);
                } finally {
                    unlock(lockKey, token);
                }
            });
        } catch (RuntimeException rejected) {
            unlock(lockKey, token);
            throw rejected;
        }
    }

    /** Redis Lua compares the DB row version with the invalidation watermark before accepting a write. */
    private boolean writeWithLogicalExpire(String key, Object id, Object value, Long ttl, TimeUnit unit) {
        long baseSec = Math.max(1L, unit.toSeconds(ttl));
        long jitter = (long) (baseSec * 0.2 * RANDOM.nextDouble());
        long logicalTtlSeconds = baseSec + jitter;
        long physicalTtlSeconds = Math.max(logicalTtlSeconds * 2, logicalTtlSeconds + 3600);

        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setVersion(versionOf(value));
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(logicalTtlSeconds));
        Long written = stringRedisTemplate.execute(
                WRITE_IF_VERSION_SCRIPT,
                Arrays.asList(key, CACHE_VENUE_VERSION_KEY + id),
                String.valueOf(redisData.getVersion()), JSONUtil.toJsonStr(redisData),
                String.valueOf(physicalTtlSeconds));
        return Long.valueOf(1L).equals(written);
    }

    private RedisData readRedis(String key) {
        String json = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isBlank(json)) return null;
        return JSONUtil.toBean(json, RedisData.class);
    }

    @SuppressWarnings("unchecked")
    private <R> R convert(RedisData redisData, Class<R> type) {
        if (redisData.getData() == null) return null;
        if (type.isInstance(redisData.getData())) return (R) redisData.getData();
        return JSONUtil.toBean(JSONUtil.parseObj(redisData.getData()), type);
    }

    private long versionOf(Object value) {
        if (value instanceof Venue) {
            Long version = ((Venue) value).getCacheVersion();
            return version == null ? 0L : version;
        }
        return 0L;
    }

    private long currentVersion(Object id) {
        String raw = stringRedisTemplate.opsForValue().get(CACHE_VENUE_VERSION_KEY + id);
        return raw == null ? 0L : Long.parseLong(raw);
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
