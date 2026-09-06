package com.hmdp.utils;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;

class SlidingWindowRateLimiterTest {

    private final SlidingWindowRateLimiter limiter = new SlidingWindowRateLimiter();

    @Test
    void rejectsInvalidWindowConfigurationBeforeCallingRedis() {
        assertThrows(IllegalArgumentException.class, () -> limiter.tryAcquire("key", 0, 5));
        assertThrows(IllegalArgumentException.class, () -> limiter.tryAcquire("key", 1000, 0));
    }
}
