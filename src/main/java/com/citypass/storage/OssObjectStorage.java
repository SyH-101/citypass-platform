package com.citypass.storage;

import com.aliyun.oss.*;
import com.aliyun.oss.common.auth.DefaultCredentialProvider;
import com.aliyun.oss.common.comm.SignVersion;
import com.aliyun.oss.model.GeneratePresignedUrlRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import javax.annotation.PreDestroy;
import java.io.InputStream;
import java.util.Date;

@Component
@ConditionalOnProperty(name = "object-storage.provider", havingValue = "oss")
public class OssObjectStorage implements ObjectStorage {
    private final OSS internal;
    private final OSS signer;
    private final String bucket;

    public OssObjectStorage(StorageProperties p) {
        if (p.getAccessKey() == null || p.getAccessKey().trim().isEmpty()
                || p.getSecretKey() == null || p.getSecretKey().trim().isEmpty()) {
            throw new IllegalArgumentException("OSS credentials must be provided via environment");
        }
        ClientBuilderConfiguration c = new ClientBuilderConfiguration();
        c.setConnectionTimeout(p.getTimeoutSeconds() * 1000);
        c.setSocketTimeout(p.getTimeoutSeconds() * 1000);
        c.setConnectionRequestTimeout(p.getTimeoutSeconds() * 1000);
        c.setMaxErrorRetry(0);
        c.setSignatureVersion(SignVersion.V4);
        internal = OSSClientBuilder.create().endpoint(p.getEndpoint()).region(p.getRegion())
                .credentialsProvider(new DefaultCredentialProvider(p.getAccessKey(), p.getSecretKey())).clientConfiguration(c).build();
        signer = OSSClientBuilder.create().endpoint(p.getPublicEndpoint()).region(p.getRegion())
                .credentialsProvider(new DefaultCredentialProvider(p.getAccessKey(), p.getSecretKey())).clientConfiguration(c).build();
        bucket = p.getBucket();
    }
    public String signPut(String key, int seconds) { return sign(key, seconds, HttpMethod.PUT); }
    public String signGet(String key, int seconds) { return sign(key, seconds, HttpMethod.GET); }
    private String sign(String key, int seconds, HttpMethod method) {
        GeneratePresignedUrlRequest request = new GeneratePresignedUrlRequest(bucket, key, method);
        request.setExpiration(new Date(System.currentTimeMillis() + seconds * 1000L));
        return signer.generatePresignedUrl(request).toString();
    }
    public long size(String key) { return internal.getObjectMetadata(bucket, key).getContentLength(); }
    public InputStream open(String key) { return internal.getObject(bucket, key).getObjectContent(); }
    public void copy(String staging, String target) { internal.copyObject(bucket, staging, bucket, target); }
    public void delete(String key) { internal.deleteObject(bucket, key); }
    @PreDestroy public void close() { internal.shutdown(); signer.shutdown(); }
}
