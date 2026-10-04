package com.citypass.storage;

import io.minio.*;
import io.minio.http.Method;
import okhttp3.OkHttpClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.concurrent.TimeUnit;

@Component
@ConditionalOnProperty(name = "object-storage.provider", havingValue = "minio", matchIfMissing = true)
public class MinioObjectStorage implements ObjectStorage {
    private final MinioClient internal;
    private final MinioClient signer;
    private final String bucket;

    public MinioObjectStorage(StorageProperties p) {
        // Only the MinIO development adapter has isolated local example credentials.
        String access = p.getAccessKey()==null || p.getAccessKey().isEmpty()?"citypass-local":p.getAccessKey();
        String secret = p.getSecretKey()==null || p.getSecretKey().isEmpty()?"citypass-local-change-me":p.getSecretKey();
        OkHttpClient http = new OkHttpClient.Builder()
                .connectTimeout(p.getTimeoutSeconds(), TimeUnit.SECONDS)
                .readTimeout(p.getTimeoutSeconds(), TimeUnit.SECONDS)
                .writeTimeout(p.getTimeoutSeconds(), TimeUnit.SECONDS)
                .callTimeout(p.getTimeoutSeconds(), TimeUnit.SECONDS).build();
        internal = MinioClient.builder().endpoint(p.getEndpoint()).region(p.getRegion())
                .credentials(access, secret).httpClient(http).build();
        // Sign for the browser endpoint itself. Do not rewrite a signed host afterwards.
        signer = MinioClient.builder().endpoint(p.getPublicEndpoint()).region(p.getRegion())
                .credentials(access, secret).httpClient(http).build();
        bucket = p.getBucket();
    }

    public String signPut(String key, int seconds) throws Exception {
        return sign(key, Method.PUT, seconds);
    }
    public String signGet(String key, int seconds) throws Exception {
        return sign(key, Method.GET, seconds);
    }
    private String sign(String key, Method method, int seconds) throws Exception {
        return signer.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                .bucket(bucket).object(key).method(method).expiry(seconds).build());
    }
    public long size(String key) throws Exception {
        return internal.statObject(StatObjectArgs.builder().bucket(bucket).object(key).build()).size();
    }
    public InputStream open(String key) throws Exception {
        return internal.getObject(GetObjectArgs.builder().bucket(bucket).object(key).build());
    }
    public void copy(String staging, String target) throws Exception {
        internal.copyObject(CopyObjectArgs.builder().bucket(bucket).object(target)
                .source(CopySource.builder().bucket(bucket).object(staging).build()).build());
    }
    public void delete(String key) throws Exception {
        internal.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(key).build());
    }
}
