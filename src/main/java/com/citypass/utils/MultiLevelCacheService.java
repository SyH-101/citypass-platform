package com.citypass.utils;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.citypass.config.CacheReliabilityProperties;
import com.citypass.entity.Venue;
import com.github.benmanes.caffeine.cache.Cache;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import javax.annotation.PreDestroy;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.citypass.utils.RedisConstants.CACHE_NULL_TTL;
import static com.citypass.utils.RedisConstants.CACHE_VENUE_VERSION_KEY;
import static com.citypass.utils.RedisConstants.LOCK_VENUE_REBUILD_KEY;

/**
 * Caffeine -> Redis -> MySQL Venue cache.
 *
 * <p>Normal cold rebuilds are serialized by a Redisson watchdog lock. Redis failures and lock
 * wait exhaustion enter a separate, non-blocking DB bulkhead so a cache outage cannot transfer
 * unbounded traffic to MySQL.</p>
 */
@Slf4j
@Component
public class MultiLevelCacheService {

    @Data
    @AllArgsConstructor
    private static class LocalEntry {
        private Object data;
        private long version;
    }

    private enum RedisReadState { HIT, MISS, UNAVAILABLE }
    private enum LockState { ACQUIRED, BUSY, UNAVAILABLE }
    private enum WriteState { ACCEPTED, STALE_REJECTED, UNAVAILABLE }

    @AllArgsConstructor
    private static class RedisReadResult {
        private final RedisReadState state;
        private final RedisData data;
    }

    @AllArgsConstructor
    private static class LockAttempt {
        private final LockState state;
        private final RLock lock;
    }

    private static final DefaultRedisScript<Long> WRITE_IF_VERSION_SCRIPT = new DefaultRedisScript<>();

    static {
        WRITE_IF_VERSION_SCRIPT.setLocation(new ClassPathResource("write-cache-if-version.lua"));
        WRITE_IF_VERSION_SCRIPT.setResultType(Long.class);
    }

    private final StringRedisTemplate stringRedisTemplate;
    private final Cache<String, Object> venueLocalCache;
    private final RedissonClient redissonClient;
    private final CacheReliabilityProperties properties;
    private final RedisFailureGate redisFailureGate;
    private final CacheReliabilityMetrics metrics;
    private final ExecutorService rebuildExecutor;
    private final Semaphore dbFallbackBulkhead;
    private final Map<String, Boolean> localRebuilds = new ConcurrentHashMap<>();

    @Autowired
    public MultiLevelCacheService(StringRedisTemplate stringRedisTemplate,
                                  @Qualifier("venueLocalCache") Cache<String, Object> venueLocalCache,
                                  RedissonClient redissonClient,
                                  CacheReliabilityProperties properties,
                                  RedisFailureGate redisFailureGate,
                                  CacheReliabilityMetrics metrics) {
        this(stringRedisTemplate, venueLocalCache, redissonClient, properties,
                redisFailureGate, metrics, Executors.newFixedThreadPool(10, r -> {
                    Thread thread = new Thread(r, "venue-cache-rebuild");
                    thread.setDaemon(true);
                    return thread;
                }));
    }

    MultiLevelCacheService(StringRedisTemplate stringRedisTemplate,
                           Cache<String, Object> venueLocalCache,
                           RedissonClient redissonClient,
                           CacheReliabilityProperties properties,
                           RedisFailureGate redisFailureGate,
                           CacheReliabilityMetrics metrics,
                           ExecutorService rebuildExecutor) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.venueLocalCache = venueLocalCache;
        this.redissonClient = redissonClient;
        this.properties = properties;
        this.redisFailureGate = redisFailureGate;
        this.metrics = metrics;
        this.rebuildExecutor = rebuildExecutor;
        this.dbFallbackBulkhead = new Semaphore(
                Math.max(1, properties.getDbFallbackMaxConcurrency()), false);
    }

    @SuppressWarnings("unchecked")
    public <R, ID> R queryWithMultiLevel(String keyPrefix, ID id, Class<R> type,
                                         Function<ID, R> dbFallback, Long ttl, TimeUnit unit) {
        String cacheKey = keyPrefix + id;
        Object cached = venueLocalCache.getIfPresent(cacheKey);
        if (cached instanceof LocalEntry) {
            metrics.caffeineHit();
            log.debug("cache.caffeine.hit key={}", cacheKey);
            return (R) ((LocalEntry) cached).getData();
        }

        RedisReadResult redisRead = readRedis(cacheKey);
        if (redisRead.state == RedisReadState.HIT) {
            return serveRedisHit(keyPrefix, id, type, dbFallback, ttl, unit, redisRead.data);
        }
        if (redisRead.state == RedisReadState.UNAVAILABLE) {
            return degradedDbFallback(cacheKey, id, dbFallback, "REDIS_UNAVAILABLE");
        }
        return queryColdMiss(keyPrefix, id, type, dbFallback, ttl, unit);
    }

    public void evictLocal(String keyPrefix, Object id) {
        venueLocalCache.invalidate(keyPrefix + id);
    }

    /** Compatibility helper for callers that own no versioned invalidation workflow. */
    public void evict(String keyPrefix, Object id) {
        evictLocal(keyPrefix, id);
        stringRedisTemplate.delete(keyPrefix + id);
    }

    private <R, ID> R queryColdMiss(String keyPrefix, ID id, Class<R> type,
                                    Function<ID, R> dbFallback, Long ttl, TimeUnit unit) {
        String cacheKey = keyPrefix + id;
        String lockKey = LOCK_VENUE_REBUILD_KEY + id;
        int attempts = Math.max(1, properties.getLockWaitAttempts());

        for (int attempt = 0; attempt < attempts; attempt++) {
            LockAttempt lockAttempt = tryRebuildLock(lockKey);
            if (lockAttempt.state == LockState.UNAVAILABLE) {
                return degradedDbFallback(cacheKey, id, dbFallback, "REDIS_LOCK_UNAVAILABLE");
            }
            if (lockAttempt.state == LockState.ACQUIRED) {
                boolean redisUnavailable = false;
                try {
                    RedisReadResult doubleChecked = readRedis(cacheKey);
                    if (doubleChecked.state == RedisReadState.UNAVAILABLE) {
                        redisUnavailable = true;
                    } else if (doubleChecked.state == RedisReadState.HIT
                            && !isLogicallyExpired(doubleChecked.data)) {
                        return serveFreshRedisData(cacheKey, type, doubleChecked.data);
                    } else {
                        return normalRebuild(cacheKey, id, dbFallback, ttl, unit);
                    }
                } finally {
                    unlockSafely(lockAttempt.lock, lockKey);
                }
                if (redisUnavailable) {
                    return degradedDbFallback(cacheKey, id, dbFallback,
                            "REDIS_DOUBLE_CHECK_UNAVAILABLE");
                }
            }

            metrics.rebuildLockBusy();
            if (!sleepBeforeRetry()) {
                log.warn("cache rebuild wait interrupted, entering controlled fallback: key={}", cacheKey);
                return degradedDbFallback(cacheKey, id, dbFallback, "LOCK_WAIT_INTERRUPTED");
            }
            RedisReadResult refreshed = readRedis(cacheKey);
            if (refreshed.state == RedisReadState.HIT) {
                return serveRedisHit(keyPrefix, id, type, dbFallback, ttl, unit, refreshed.data);
            }
            if (refreshed.state == RedisReadState.UNAVAILABLE) {
                return degradedDbFallback(cacheKey, id, dbFallback, "REDIS_WAIT_UNAVAILABLE");
            }
        }

        log.warn("cache rebuild lock wait exhausted, entering controlled fallback: key={}, attempts={}",
                cacheKey, attempts);
        return degradedDbFallback(cacheKey, id, dbFallback, "LOCK_WAIT_EXHAUSTED");
    }

    private <R, ID> R normalRebuild(String cacheKey, ID id, Function<ID, R> dbFallback,
                                    Long ttl, TimeUnit unit) {
        metrics.rebuildStarted();
        log.debug("normal cache rebuild started: key={}", cacheKey);
        R result = dbFallback.apply(id);
        if (result == null) {
            writeNullMarker(cacheKey, id);
            return null;
        }
        WriteState state = writeWithLogicalExpire(cacheKey, id, result, ttl, unit);
        if (state == WriteState.ACCEPTED) {
            venueLocalCache.put(cacheKey, new LocalEntry(result, versionOf(result)));
        } else {
            venueLocalCache.invalidate(cacheKey);
        }
        return result;
    }

    private <R, ID> R degradedDbFallback(String cacheKey, ID id,
                                          Function<ID, R> dbFallback, String reason) {
        if (!dbFallbackBulkhead.tryAcquire()) {
            metrics.dbFallbackRejected();
            log.warn("degraded DB fallback rejected: key={}, reason={}, limit={}", cacheKey,
                    reason, Math.max(1, properties.getDbFallbackMaxConcurrency()));
            throw new CacheDegradedException("缓存服务暂时不可用，请稍后重试");
        }
        metrics.dbFallbackAccepted();
        log.warn("degraded DB fallback accepted: key={}, reason={}", cacheKey, reason);
        try {
            R result = dbFallback.apply(id);
            if (result != null) {
                venueLocalCache.put(cacheKey, new LocalEntry(result, versionOf(result)));
            }
            return result;
        } finally {
            dbFallbackBulkhead.release();
        }
    }

    private <R, ID> R serveRedisHit(String keyPrefix, ID id, Class<R> type,
                                    Function<ID, R> dbFallback, Long ttl, TimeUnit unit,
                                    RedisData redisData) {
        if (Boolean.TRUE.equals(redisData.getNullValue())) {
            metrics.nullMarkerHit();
            return null;
        }
        R data = convert(redisData, type);
        if (!isLogicallyExpired(redisData)) {
            String cacheKey = keyPrefix + id;
            venueLocalCache.put(cacheKey, new LocalEntry(data, versionOfRedisData(redisData)));
            return data;
        }
        rebuildAsync(keyPrefix, id, type, dbFallback, ttl, unit);
        return data;
    }

    private <R> R serveFreshRedisData(String cacheKey, Class<R> type, RedisData redisData) {
        if (Boolean.TRUE.equals(redisData.getNullValue())) {
            metrics.nullMarkerHit();
            return null;
        }
        R data = convert(redisData, type);
        venueLocalCache.put(cacheKey, new LocalEntry(data, versionOfRedisData(redisData)));
        return data;
    }

    private <R, ID> void rebuildAsync(String keyPrefix, ID id, Class<R> type,
                                      Function<ID, R> dbFallback, Long ttl, TimeUnit unit) {
        String cacheKey = keyPrefix + id;
        if (localRebuilds.putIfAbsent(cacheKey, Boolean.TRUE) != null) return;
        try {
            rebuildExecutor.submit(() -> {
                RLock lock = null;
                String lockKey = LOCK_VENUE_REBUILD_KEY + id;
                try {
                    LockAttempt lockAttempt = tryRebuildLock(lockKey);
                    if (lockAttempt.state != LockState.ACQUIRED) {
                        if (lockAttempt.state == LockState.BUSY) metrics.rebuildLockBusy();
                        return;
                    }
                    lock = lockAttempt.lock;

                    RedisReadResult latest = readRedis(cacheKey);
                    if (latest.state == RedisReadState.UNAVAILABLE) {
                        // The caller already received stale data; avoid DB pressure during an outage.
                        return;
                    }
                    if (latest.state == RedisReadState.HIT && !isLogicallyExpired(latest.data)) {
                        serveFreshRedisData(cacheKey, type, latest.data);
                        return;
                    }

                    metrics.rebuildStarted();
                    log.debug("normal asynchronous cache rebuild started: key={}", cacheKey);
                    R data = dbFallback.apply(id);
                    if (data == null) {
                        writeNullMarker(cacheKey, id);
                        venueLocalCache.invalidate(cacheKey);
                    } else if (writeWithLogicalExpire(cacheKey, id, data, ttl, unit)
                            == WriteState.ACCEPTED) {
                        venueLocalCache.put(cacheKey, new LocalEntry(data, versionOf(data)));
                    } else {
                        venueLocalCache.invalidate(cacheKey);
                    }
                } catch (RuntimeException e) {
                    log.error("asynchronous Venue cache rebuild failed: key={}", cacheKey, e);
                } finally {
                    unlockSafely(lock, lockKey);
                    localRebuilds.remove(cacheKey);
                }
            });
        } catch (RuntimeException rejected) {
            localRebuilds.remove(cacheKey);
            log.warn("Venue cache rebuild task rejected: key={}", cacheKey, rejected);
        }
    }

    private WriteState writeWithLogicalExpire(String key, Object id, Object value,
                                              Long ttl, TimeUnit unit) {
        RedisFailureGate.Permission permission = redisFailureGate.tryAcquire();
        if (permission == RedisFailureGate.Permission.REJECTED) {
            metrics.redisBypassed();
            return WriteState.UNAVAILABLE;
        }
        try {
            long baseSec = Math.max(1L, unit.toSeconds(ttl));
            long jitter = (long) (baseSec * 0.2 * ThreadLocalRandom.current().nextDouble());
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
            redisFailureGate.onSuccess(permission);
            return Long.valueOf(1L).equals(written)
                    ? WriteState.ACCEPTED : WriteState.STALE_REJECTED;
        } catch (RuntimeException e) {
            recordRedisFailure(permission, "cache write", key, e);
            return WriteState.UNAVAILABLE;
        }
    }

    private void writeNullMarker(String key, Object id) {
        RedisFailureGate.Permission permission = redisFailureGate.tryAcquire();
        if (permission == RedisFailureGate.Permission.REJECTED) {
            metrics.redisBypassed();
            return;
        }
        try {
            String rawVersion = stringRedisTemplate.opsForValue().get(CACHE_VENUE_VERSION_KEY + id);
            long version = rawVersion == null ? 0L : Long.parseLong(rawVersion);
            RedisData nullData = new RedisData();
            nullData.setNullValue(true);
            nullData.setVersion(version);
            nullData.setExpireTime(LocalDateTime.now().plusMinutes(CACHE_NULL_TTL));
            stringRedisTemplate.opsForValue().set(
                    key, JSONUtil.toJsonStr(nullData), CACHE_NULL_TTL, TimeUnit.MINUTES);
            redisFailureGate.onSuccess(permission);
        } catch (RuntimeException e) {
            recordRedisFailure(permission, "null marker write", key, e);
        }
    }

    private RedisReadResult readRedis(String key) {
        RedisFailureGate.Permission permission = redisFailureGate.tryAcquire();
        if (permission == RedisFailureGate.Permission.REJECTED) {
            metrics.redisBypassed();
            log.debug("Redis call bypassed while failure gate is {}: key={}",
                    redisFailureGate.stateName(), key);
            return new RedisReadResult(RedisReadState.UNAVAILABLE, null);
        }
        try {
            String json = stringRedisTemplate.opsForValue().get(key);
            redisFailureGate.onSuccess(permission);
            if (StrUtil.isBlank(json)) {
                metrics.redisMiss();
                log.debug("cache.redis.miss key={}", key);
                return new RedisReadResult(RedisReadState.MISS, null);
            }
            RedisData data = JSONUtil.toBean(json, RedisData.class);
            metrics.redisHit();
            return new RedisReadResult(RedisReadState.HIT, data);
        } catch (RuntimeException e) {
            recordRedisFailure(permission, "cache read", key, e);
            return new RedisReadResult(RedisReadState.UNAVAILABLE, null);
        }
    }

    private LockAttempt tryRebuildLock(String key) {
        RedisFailureGate.Permission permission = redisFailureGate.tryAcquire();
        if (permission == RedisFailureGate.Permission.REJECTED) {
            metrics.redisBypassed();
            return new LockAttempt(LockState.UNAVAILABLE, null);
        }
        try {
            RLock lock = redissonClient.getLock(key);
            // No lease time is supplied: Redisson's watchdog renews the lock while this owner lives.
            boolean acquired = lock.tryLock();
            redisFailureGate.onSuccess(permission);
            return new LockAttempt(acquired ? LockState.ACQUIRED : LockState.BUSY,
                    acquired ? lock : null);
        } catch (RuntimeException e) {
            recordRedisFailure(permission, "rebuild lock", key, e);
            return new LockAttempt(LockState.UNAVAILABLE, null);
        }
    }

    private void unlockSafely(RLock lock, String key) {
        if (lock == null) return;
        try {
            if (lock.isHeldByCurrentThread()) lock.unlock();
        } catch (RuntimeException e) {
            metrics.redisError();
            log.warn("Redis rebuild lock release failed: key={}, error={}", key, e.toString());
        }
    }

    private void recordRedisFailure(RedisFailureGate.Permission permission, String operation,
                                    String key, RuntimeException e) {
        redisFailureGate.onFailure(permission);
        metrics.redisError();
        log.warn("cache.redis.error operation={}, key={}, gate={}, error={}", operation, key,
                redisFailureGate.stateName(), e.toString());
    }

    private boolean sleepBeforeRetry() {
        long min = Math.max(0L, properties.getLockWaitMinDelayMs());
        long max = Math.max(min, properties.getLockWaitMaxDelayMs());
        long delay = max == min ? min : ThreadLocalRandom.current().nextLong(min, max + 1L);
        try {
            if (delay > 0L) Thread.sleep(delay);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private boolean isLogicallyExpired(RedisData redisData) {
        return redisData.getExpireTime() == null
                || !redisData.getExpireTime().isAfter(LocalDateTime.now());
    }

    private long versionOfRedisData(RedisData redisData) {
        return redisData.getVersion() == null ? 0L : redisData.getVersion();
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

    @PreDestroy
    public void shutdown() {
        rebuildExecutor.shutdown();
    }
}
