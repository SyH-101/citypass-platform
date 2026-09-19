package com.citypass.utils;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/** Micrometer signals for the Venue cache read path and its degradation boundary. */
@Component
public class CacheReliabilityMetrics {

    private final Counter caffeineHit;
    private final Counter redisHit;
    private final Counter redisMiss;
    private final Counter redisError;
    private final Counter redisBypassed;
    private final Counter rebuildStarted;
    private final Counter rebuildLockBusy;
    private final Counter dbFallbackAccepted;
    private final Counter dbFallbackRejected;
    private final Counter nullMarkerHit;

    public CacheReliabilityMetrics(MeterRegistry registry, RedisFailureGate redisFailureGate) {
        caffeineHit = registry.counter("cache.caffeine.hit");
        redisHit = registry.counter("cache.redis.hit");
        redisMiss = registry.counter("cache.redis.miss");
        redisError = registry.counter("cache.redis.error");
        redisBypassed = registry.counter("cache.redis.bypassed");
        rebuildStarted = registry.counter("cache.rebuild.started");
        rebuildLockBusy = registry.counter("cache.rebuild.lock.busy");
        dbFallbackAccepted = registry.counter("cache.db.fallback.accepted");
        dbFallbackRejected = registry.counter("cache.db.fallback.rejected");
        nullMarkerHit = registry.counter("cache.null.marker.hit");
        Gauge.builder("cache.redis.failure.gate.state", redisFailureGate,
                RedisFailureGate::stateCode).register(registry);
    }

    public void caffeineHit() { caffeineHit.increment(); }
    public void redisHit() { redisHit.increment(); }
    public void redisMiss() { redisMiss.increment(); }
    public void redisError() { redisError.increment(); }
    public void redisBypassed() { redisBypassed.increment(); }
    public void rebuildStarted() { rebuildStarted.increment(); }
    public void rebuildLockBusy() { rebuildLockBusy.increment(); }
    public void dbFallbackAccepted() { dbFallbackAccepted.increment(); }
    public void dbFallbackRejected() { dbFallbackRejected.increment(); }
    public void nullMarkerHit() { nullMarkerHit.increment(); }
}
