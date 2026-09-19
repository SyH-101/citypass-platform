package com.citypass.utils;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RedisFailureGateTest {

    @Test
    void opensThenAllowsOnlyOneHalfOpenProbeAndClosesOnRecovery() {
        AtomicLong clock = new AtomicLong();
        RedisFailureGate gate = new RedisFailureGate(2, 100, clock::get);

        RedisFailureGate.Permission first = gate.tryAcquire();
        gate.onFailure(first);
        assertEquals("CLOSED", gate.stateName());

        RedisFailureGate.Permission second = gate.tryAcquire();
        gate.onFailure(second);
        assertEquals("OPEN", gate.stateName());
        assertEquals(RedisFailureGate.Permission.REJECTED, gate.tryAcquire());

        clock.addAndGet(100_000_000L);
        RedisFailureGate.Permission probe = gate.tryAcquire();
        assertEquals(RedisFailureGate.Permission.PROBE, probe);
        assertEquals(RedisFailureGate.Permission.REJECTED, gate.tryAcquire());

        gate.onSuccess(probe);
        assertEquals("CLOSED", gate.stateName());
        assertEquals(RedisFailureGate.Permission.NORMAL, gate.tryAcquire());
    }

    @Test
    void ordinaryRedisMissReportedAsSuccessDoesNotOpenGate() {
        AtomicLong clock = new AtomicLong();
        RedisFailureGate gate = new RedisFailureGate(1, 100, clock::get);

        RedisFailureGate.Permission permission = gate.tryAcquire();
        gate.onSuccess(permission);

        assertEquals("CLOSED", gate.stateName());
        assertEquals(RedisFailureGate.Permission.NORMAL, gate.tryAcquire());
    }
}
