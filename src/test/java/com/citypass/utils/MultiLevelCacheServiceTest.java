package com.citypass.utils;

import cn.hutool.json.JSONUtil;
import com.citypass.config.CacheReliabilityProperties;
import com.citypass.entity.Venue;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;

import static com.citypass.utils.RedisConstants.CACHE_VENUE_KEY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MultiLevelCacheServiceTest {

    private final List<Fixture> fixtures = new ArrayList<>();

    @AfterEach
    void tearDown() {
        fixtures.forEach(Fixture::close);
    }

    @Test
    void nullMarkerPreventsSecondDatabaseLookup() {
        Fixture fixture = fixture(2, 3, 20, 1);
        AtomicInteger dbCalls = new AtomicInteger();
        Function<Long, Venue> missing = id -> {
            dbCalls.incrementAndGet();
            return null;
        };

        assertEquals(null, fixture.query(999999L, missing));
        assertEquals(null, fixture.query(999999L, missing));

        assertEquals(1, dbCalls.get());
        assertEquals(1.0, fixture.registry.get("cache.null.marker.hit").counter().count());
    }

    @Test
    void twoHundredConcurrentColdMissesPerformOneNormalDatabaseRebuild() throws Exception {
        Fixture fixture = fixture(4, 3, 400, 10);
        AtomicInteger dbCalls = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        Function<Long, Venue> database = id -> {
            dbCalls.incrementAndGet();
            sleep(150);
            return venue(id, 1L, "hot");
        };

        ExecutorService callers = Executors.newFixedThreadPool(48);
        List<Future<Venue>> futures = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            futures.add(callers.submit(() -> {
                start.await();
                return fixture.query(1L, database);
            }));
        }
        start.countDown();
        for (Future<Venue> future : futures) assertEquals("hot", future.get(5, TimeUnit.SECONDS).getName());
        callers.shutdownNow();

        assertEquals(1, dbCalls.get());
    }

    @Test
    void rebuildLongerThanOldTenSecondLeaseDoesNotAdmitSecondNormalRebuilder() throws Exception {
        Fixture fixture = fixture(1, 3, 260, 50);
        AtomicInteger dbCalls = new AtomicInteger();
        CountDownLatch firstEnteredDb = new CountDownLatch(1);
        Function<Long, Venue> slowDatabase = id -> {
            dbCalls.incrementAndGet();
            firstEnteredDb.countDown();
            sleep(10_200);
            return venue(id, 2L, "slow");
        };

        ExecutorService callers = Executors.newFixedThreadPool(2);
        Future<Venue> first = callers.submit(() -> fixture.query(2L, slowDatabase));
        assertTrue(firstEnteredDb.await(2, TimeUnit.SECONDS));
        Future<Venue> second = callers.submit(() -> fixture.query(2L, slowDatabase));

        assertEquals("slow", first.get(14, TimeUnit.SECONDS).getName());
        assertEquals("slow", second.get(14, TimeUnit.SECONDS).getName());
        callers.shutdownNow();

        assertEquals(1, dbCalls.get());
        verify(fixture.lock, never()).tryLock(anyLong(), anyLong(), any(TimeUnit.class));
    }

    @Test
    void exhaustedLockWaitUsesNonBlockingBulkhead() throws Exception {
        Fixture fixture = fixture(2, 3, 1, 0);
        fixture.forceLockBusy.set(true);
        AtomicInteger dbCalls = new AtomicInteger();
        CountDownLatch accepted = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        Function<Long, Venue> database = id -> {
            dbCalls.incrementAndGet();
            accepted.countDown();
            await(release);
            return venue(id, 1L, "fallback");
        };

        List<Future<?>> futures = submitConcurrent(32, index -> fixture.query(10_000L + index, database));
        assertTrue(accepted.await(2, TimeUnit.SECONDS));
        sleep(200);
        release.countDown();
        int rejected = countDegradedFailures(futures);

        assertEquals(2, dbCalls.get());
        assertEquals(30, rejected);
        assertEquals(2.0, fixture.registry.get("cache.db.fallback.accepted").counter().count());
        assertEquals(30.0, fixture.registry.get("cache.db.fallback.rejected").counter().count());
    }

    @Test
    void redisOutageKeepsCaffeineHitsAndBoundsAllMissFallbacks() throws Exception {
        Fixture fixture = fixture(2, 1, 1, 0);
        fixture.store.put(CACHE_VENUE_KEY + 1L, redisData(venue(1L, 1L, "warm"), false));
        AtomicInteger warmDbCalls = new AtomicInteger();
        assertEquals("warm", fixture.query(1L, id -> {
            warmDbCalls.incrementAndGet();
            return null;
        }).getName());

        fixture.redisDown.set(true);
        assertEquals("warm", fixture.query(1L, id -> {
            warmDbCalls.incrementAndGet();
            return null;
        }).getName());
        assertEquals(0, warmDbCalls.get());

        AtomicInteger degradedDbCalls = new AtomicInteger();
        CountDownLatch accepted = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        Function<Long, Venue> database = id -> {
            degradedDbCalls.incrementAndGet();
            accepted.countDown();
            await(release);
            return venue(id, 1L, "degraded");
        };
        List<Future<?>> futures = submitConcurrent(32,
                index -> fixture.query(20_000L + index, database));
        assertTrue(accepted.await(2, TimeUnit.SECONDS));
        sleep(200);
        release.countDown();
        int rejected = countDegradedFailures(futures);

        assertEquals(2, degradedDbCalls.get());
        assertEquals(30, rejected);
    }

    @Test
    void halfOpenProbeRestoresNormalRedisPath() {
        Fixture fixture = fixture(1, 1, 1, 0);
        AtomicInteger dbCalls = new AtomicInteger();
        fixture.redisDown.set(true);
        assertEquals(null, fixture.query(77L, id -> {
            dbCalls.incrementAndGet();
            return null;
        }));
        assertEquals("OPEN", fixture.gate.stateName());

        fixture.redisDown.set(false);
        fixture.store.put(CACHE_VENUE_KEY + 77L, redisData(venue(77L, 3L, "recovered"), false));
        sleep(80);
        Venue recovered = fixture.query(77L, id -> {
            dbCalls.incrementAndGet();
            return null;
        });

        assertEquals("recovered", recovered.getName());
        assertEquals(1, dbCalls.get());
        assertEquals("CLOSED", fixture.gate.stateName());
    }

    @Test
    void logicalExpiryReturnsStaleAndSchedulesOnlyOneRebuild() throws Exception {
        Fixture fixture = fixture(2, 3, 20, 1);
        fixture.store.put(CACHE_VENUE_KEY + 88L, redisData(venue(88L, 1L, "stale"), true));
        AtomicInteger dbCalls = new AtomicInteger();
        CountDownLatch rebuildStarted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Function<Long, Venue> database = id -> {
            dbCalls.incrementAndGet();
            rebuildStarted.countDown();
            await(release);
            return venue(id, 2L, "fresh");
        };

        List<Future<?>> futures = submitConcurrent(100, index -> {
            Venue result = fixture.query(88L, database);
            assertEquals("stale", result.getName());
            return result;
        });
        assertTrue(rebuildStarted.await(2, TimeUnit.SECONDS));
        for (Future<?> future : futures) future.get(2, TimeUnit.SECONDS);
        assertEquals(1, dbCalls.get());
        release.countDown();

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (fixture.registry.get("cache.rebuild.started").counter().count() < 1.0
                && System.nanoTime() < deadline) sleep(10);
        assertEquals(1.0, fixture.registry.get("cache.rebuild.started").counter().count());
    }

    private Fixture fixture(int bulkhead, int threshold, int waitAttempts, long waitDelayMs) {
        Fixture fixture = new Fixture(bulkhead, threshold, waitAttempts, waitDelayMs);
        fixtures.add(fixture);
        return fixture;
    }

    private List<Future<?>> submitConcurrent(int count, ThrowingFunction<Integer> task) {
        ExecutorService callers = Executors.newFixedThreadPool(count);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            final int index = i;
            futures.add(callers.submit(() -> {
                start.await();
                return task.apply(index);
            }));
        }
        start.countDown();
        callers.shutdown();
        return futures;
    }

    private int countDegradedFailures(List<Future<?>> futures) throws Exception {
        int rejected = 0;
        for (Future<?> future : futures) {
            try {
                future.get(3, TimeUnit.SECONDS);
            } catch (java.util.concurrent.ExecutionException e) {
                if (e.getCause() instanceof CacheDegradedException) rejected++;
                else throw e;
            }
        }
        return rejected;
    }

    private static Venue venue(long id, long version, String name) {
        return new Venue().setId(id).setCacheVersion(version).setName(name);
    }

    private static String redisData(Venue venue, boolean expired) {
        RedisData data = new RedisData();
        data.setData(venue);
        data.setVersion(venue.getCacheVersion());
        data.setExpireTime(expired
                ? LocalDateTime.now().minusSeconds(1)
                : LocalDateTime.now().plusMinutes(5));
        return JSONUtil.toJsonStr(data);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) throw new AssertionError("latch timed out");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    @FunctionalInterface
    private interface ThrowingFunction<T> {
        Object apply(T value) throws Exception;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static class Fixture implements AutoCloseable {
        private final Map<String, String> store = new ConcurrentHashMap<>();
        private final AtomicBoolean redisDown = new AtomicBoolean();
        private final AtomicBoolean forceLockBusy = new AtomicBoolean();
        private final ReentrantLock backendLock = new ReentrantLock();
        private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
        private final ValueOperations<String, String> values = mock(ValueOperations.class);
        private final RedissonClient redisson = mock(RedissonClient.class);
        private final RLock lock = mock(RLock.class);
        private final Cache<String, Object> caffeine = Caffeine.newBuilder().maximumSize(1000).build();
        private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
        private final ExecutorService rebuildExecutor = Executors.newFixedThreadPool(4);
        private final RedisFailureGate gate;
        private final MultiLevelCacheService service;

        private Fixture(int bulkhead, int threshold, int waitAttempts, long waitDelayMs) {
            CacheReliabilityProperties properties = new CacheReliabilityProperties();
            properties.setDbFallbackMaxConcurrency(bulkhead);
            properties.setRedisFailureThreshold(threshold);
            properties.setRedisOpenDurationMs(50);
            properties.setLockWaitAttempts(waitAttempts);
            properties.setLockWaitMinDelayMs(waitDelayMs);
            properties.setLockWaitMaxDelayMs(waitDelayMs);
            gate = new RedisFailureGate(properties);
            CacheReliabilityMetrics metrics = new CacheReliabilityMetrics(registry, gate);

            when(redis.opsForValue()).thenReturn(values);
            when(values.get(anyString())).thenAnswer(invocation -> {
                failIfRedisDown();
                return store.get(invocation.getArgument(0));
            });
            doAnswer(invocation -> {
                failIfRedisDown();
                store.put(invocation.getArgument(0), invocation.getArgument(1));
                return null;
            }).when(values).set(anyString(), anyString(), anyLong(), any(TimeUnit.class));
            doAnswer(invocation -> {
                failIfRedisDown();
                List<String> keys = invocation.getArgument(1);
                String json = invocation.getArgument(3);
                store.put(keys.get(0), json);
                return 1L;
            }).when(redis).execute(any(RedisScript.class), anyList(), any(), any(), any());

            when(redisson.getLock(anyString())).thenReturn(lock);
            when(lock.tryLock()).thenAnswer(invocation ->
                    !forceLockBusy.get() && backendLock.tryLock());
            when(lock.isHeldByCurrentThread()).thenAnswer(invocation -> backendLock.isHeldByCurrentThread());
            doAnswer(invocation -> {
                backendLock.unlock();
                return null;
            }).when(lock).unlock();

            service = new MultiLevelCacheService(redis, caffeine, redisson, properties,
                    gate, metrics, rebuildExecutor);
        }

        private Venue query(Long id, Function<Long, Venue> database) {
            return service.queryWithMultiLevel(
                    CACHE_VENUE_KEY, id, Venue.class, database, 30L, TimeUnit.MINUTES);
        }

        private void failIfRedisDown() {
            if (redisDown.get()) throw new IllegalStateException("Redis unavailable");
        }

        @Override
        public void close() {
            service.shutdown();
            rebuildExecutor.shutdownNow();
            registry.close();
        }
    }
}
