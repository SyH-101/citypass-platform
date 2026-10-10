package com.citypass.storage;

import io.minio.*;
import io.minio.http.Method;
import okhttp3.OkHttpClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import java.io.InputStream;
import java.util.concurrent.TimeUnit;

@Component
@ConditionalOnProperty(
        name = "object-storage.provider",
        havingValue = "minio",
        matchIfMissing = true)
public class MinioObjectStorage implements ObjectStorage {

    private final MinioClient internalClient;
    private final MinioClient signingClient;
    private final String bucket;

    public MinioObjectStorage(StorageProperties storageProperties) {
        // Only the MinIO development adapter has isolated local example credentials.
        String accessKey =
                storageProperties.getAccessKey() == null
                                || storageProperties.getAccessKey().isEmpty()
                        ? "citypass-local"
                        : storageProperties.getAccessKey();
        String secretKey =
                storageProperties.getSecretKey() == null
                                || storageProperties.getSecretKey().isEmpty()
                        ? "citypass-local-change-me"
                        : storageProperties.getSecretKey();
        OkHttpClient httpClient =
                new OkHttpClient.Builder()
                        .connectTimeout(storageProperties.getTimeoutSeconds(), TimeUnit.SECONDS)
                        .readTimeout(storageProperties.getTimeoutSeconds(), TimeUnit.SECONDS)
                        .writeTimeout(storageProperties.getTimeoutSeconds(), TimeUnit.SECONDS)
                        .callTimeout(storageProperties.getTimeoutSeconds(), TimeUnit.SECONDS)
                        .build();
        internalClient =
                MinioClient.builder()
                        .endpoint(storageProperties.getEndpoint())
                        .region(storageProperties.getRegion())
                        .credentials(accessKey, secretKey)
                        .httpClient(httpClient)
                        .build();
        // Sign for the browser endpoint itself. Do not rewrite a signed host afterwards.
        signingClient =
                MinioClient.builder()
                        .endpoint(storageProperties.getPublicEndpoint())
                        .region(storageProperties.getRegion())
                        .credentials(accessKey, secretKey)
                        .httpClient(httpClient)
                        .build();
        bucket = storageProperties.getBucket();
    }

    public String signPut(String key, int seconds) throws Exception {
        return sign(key, Method.PUT, seconds);
    }

    public String signGet(String key, int seconds) throws Exception {
        return sign(key, Method.GET, seconds);
    }

    private String sign(String key, Method method, int seconds) throws Exception {
        return signingClient.getPresignedObjectUrl(
                GetPresignedObjectUrlArgs.builder()
                        .bucket(bucket)
                        .object(key)
                        .method(method)
                        .expiry(seconds)
                        .build());
    }

    public long size(String key) throws Exception {
        return internalClient
                .statObject(StatObjectArgs.builder().bucket(bucket).object(key).build())
                .size();
    }

    public InputStream open(String key) throws Exception {
        return internalClient.getObject(GetObjectArgs.builder().bucket(bucket).object(key).build());
    }

    public void copy(String staging, String target) throws Exception {
        internalClient.copyObject(
                CopyObjectArgs.builder()
                        .bucket(bucket)
                        .object(target)
                        .source(CopySource.builder().bucket(bucket).object(staging).build())
                        .build());
    }

    public void delete(String key) throws Exception {
        internalClient.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(key).build());
    }
}
