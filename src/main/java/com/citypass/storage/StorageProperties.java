package com.citypass.storage;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "object-storage")
public class StorageProperties {
    private String provider = "minio";
    private String endpoint = "http://127.0.0.1:9000";
    private String publicEndpoint = "http://localhost:9000";
    private String region = "us-east-1";
    private String bucket = "citypass-story-files";
    private String accessKey;
    private String secretKey;
    private int timeoutSeconds = 10;
}
