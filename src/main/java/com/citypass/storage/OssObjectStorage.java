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

    private final OSS internalClient;
    private final OSS signingClient;
    private final String bucket;

    public OssObjectStorage(StorageProperties storageProperties) {
        if (storageProperties.getAccessKey() == null
                || storageProperties.getAccessKey().trim().isEmpty()
                || storageProperties.getSecretKey() == null
                || storageProperties.getSecretKey().trim().isEmpty()) {
            throw new IllegalArgumentException("OSS credentials must be provided via environment");
        }

        ClientBuilderConfiguration clientConfiguration = new ClientBuilderConfiguration();
        clientConfiguration.setConnectionTimeout(storageProperties.getTimeoutSeconds() * 1000);
        clientConfiguration.setSocketTimeout(storageProperties.getTimeoutSeconds() * 1000);
        clientConfiguration.setConnectionRequestTimeout(
                storageProperties.getTimeoutSeconds() * 1000);
        clientConfiguration.setMaxErrorRetry(0);
        clientConfiguration.setSignatureVersion(SignVersion.V4);
        internalClient =
                OSSClientBuilder.create()
                        .endpoint(storageProperties.getEndpoint())
                        .region(storageProperties.getRegion())
                        .credentialsProvider(
                                new DefaultCredentialProvider(
                                        storageProperties.getAccessKey(),
                                        storageProperties.getSecretKey()))
                        .clientConfiguration(clientConfiguration)
                        .build();
        signingClient =
                OSSClientBuilder.create()
                        .endpoint(storageProperties.getPublicEndpoint())
                        .region(storageProperties.getRegion())
                        .credentialsProvider(
                                new DefaultCredentialProvider(
                                        storageProperties.getAccessKey(),
                                        storageProperties.getSecretKey()))
                        .clientConfiguration(clientConfiguration)
                        .build();
        bucket = storageProperties.getBucket();
    }

    public String signPut(String key, int seconds) {
        return sign(key, seconds, HttpMethod.PUT);
    }

    public String signGet(String key, int seconds) {
        return sign(key, seconds, HttpMethod.GET);
    }

    private String sign(String key, int seconds, HttpMethod method) {
        GeneratePresignedUrlRequest request = new GeneratePresignedUrlRequest(bucket, key, method);
        request.setExpiration(new Date(System.currentTimeMillis() + seconds * 1000L));
        return signingClient.generatePresignedUrl(request).toString();
    }

    public long size(String key) {
        return internalClient.getObjectMetadata(bucket, key).getContentLength();
    }

    public InputStream open(String key) {
        return internalClient.getObject(bucket, key).getObjectContent();
    }

    public void copy(String staging, String target) {
        internalClient.copyObject(bucket, staging, bucket, target);
    }

    public void delete(String key) {
        internalClient.deleteObject(bucket, key);
    }

    @PreDestroy
    public void close() {
        internalClient.shutdown();
        signingClient.shutdown();
    }
}
