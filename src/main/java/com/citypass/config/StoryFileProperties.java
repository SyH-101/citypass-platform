package com.citypass.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Limits are enforced on actual object bytes and decoded image dimensions.
 */
@Data
@Component
@ConfigurationProperties(prefix = "story-files")
public class StoryFileProperties {

    private long maxBytes = 10 * 1024 * 1024;
    private long maxPixels = 20000000;
    private int maxDimension = 10000;
    private int maxImages = 9;
    private int pendingQuota = 20;
    private int uploadSeconds = 300;
    private int readSeconds = 120;
    private int draftHours = 24;
    private int cleanupGraceSeconds = 120;
    private int confirmationLeaseSeconds = 60;
    private int ioConcurrency = 4;
    private int sweepBatch = 100;
    private int tombstoneRescanSeconds = 3600;
    private int feedBatch = 200;
    private boolean debugPageEnabled = false;
}
