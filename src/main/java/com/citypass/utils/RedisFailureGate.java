package com.citypass.utils;

import com.citypass.config.CacheReliabilityProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/** Thread-safe CLOSED / OPEN / HALF_OPEN gate for cache Redis calls. */
@Component
public class RedisFailureGate {

    public enum Permission { NORMAL, PROBE, REJECTED }
    private enum State { CLOSED, OPEN, HALF_OPEN }

    private final int failureThreshold;
    private final long openDurationNanos;
    private final LongSupplier nanoTime;
    private State state = State.CLOSED;
    private int consecutiveFailures;
    private long reopenAtNanos;

    @Autowired
    public RedisFailureGate(CacheReliabilityProperties properties) {
        this(properties.getRedisFailureThreshold(), properties.getRedisOpenDurationMs(), System::nanoTime);
    }

    RedisFailureGate(int failureThreshold, long openDurationMs, LongSupplier nanoTime) {
        this.failureThreshold = Math.max(1, failureThreshold);
        this.openDurationNanos = TimeUnit.MILLISECONDS.toNanos(Math.max(1L, openDurationMs));
        this.nanoTime = nanoTime;
    }

    public synchronized Permission tryAcquire() {
        if (state == State.CLOSED) return Permission.NORMAL;
        if (state == State.OPEN && nanoTime.getAsLong() >= reopenAtNanos) {
            state = State.HALF_OPEN;
            return Permission.PROBE;
        }
        return Permission.REJECTED;
    }

    public synchronized void onSuccess(Permission permission) {
        if (permission == Permission.PROBE) {
            state = State.CLOSED;
            consecutiveFailures = 0;
            reopenAtNanos = 0L;
        } else if (permission == Permission.NORMAL && state == State.CLOSED) {
            consecutiveFailures = 0;
        }
    }

    public synchronized void onFailure(Permission permission) {
        if (permission == Permission.REJECTED) return;
        if (permission == Permission.PROBE) {
            open();
        } else if (state == State.CLOSED && ++consecutiveFailures >= failureThreshold) {
            open();
        }
    }

    public synchronized int stateCode() { return state.ordinal(); }
    public synchronized String stateName() { return state.name(); }

    private void open() {
        state = State.OPEN;
        reopenAtNanos = nanoTime.getAsLong() + openDurationNanos;
    }
}
