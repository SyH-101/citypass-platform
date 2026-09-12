package com.citypass.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "search")
public class ActivitySearchProperties {
    private boolean enabled = false;
    private String hosts = "http://127.0.0.1:9200";
    private String alias = "citypass-activity-search";
    private String cursorSecret = "";
    private int connectTimeoutMs = 1000;
    private int socketTimeoutMs = 3000;
    private int requestTimeoutMs = 3000;
    private int pitKeepAliveSeconds = 60;
    private int maxPageSize = 50;
    private int maxRadiusMeters = 50000;
    private int maxSupplementBatches = 3;
    private int rebuildBatchSize = 200;
}
