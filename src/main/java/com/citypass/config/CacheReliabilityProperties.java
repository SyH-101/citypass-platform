package com.citypass.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Tunable reliability boundaries for the Venue multi-level cache. */
@Data
@Component
@ConfigurationProperties(prefix = "cache.reliability")
public class CacheReliabilityProperties {

    /** Consecutive Redis failures before requests temporarily bypass Redis. */
    private int redisFailureThreshold = 3;

    /** OPEN duration before one HALF_OPEN Redis probe is allowed. */
    private long redisOpenDurationMs = 3000L;

    /** Maximum concurrent degraded DB reads in one JVM; acquisition never waits. */
    private int dbFallbackMaxConcurrency = 8;

    /** Number of lock-busy polling rounds before entering controlled degradation. */
    private int lockWaitAttempts = 20;

    private long lockWaitMinDelayMs = 25L;
    private long lockWaitMaxDelayMs = 50L;
}
