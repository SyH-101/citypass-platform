package com.citypass.storage;

import org.junit.jupiter.api.Test;
import java.net.URI;
import static org.junit.jupiter.api.Assertions.*;

class StorageSigningTest {

    @Test
    void minioSignsActualPublicHostAndFixedKeyWithoutNetworking() throws Exception {
        StorageProperties storageProperties = new StorageProperties();
        storageProperties.setEndpoint("http://minio:9000");
        storageProperties.setPublicEndpoint("http://localhost:19000");
        MinioObjectStorage storage = new MinioObjectStorage(storageProperties);
        URI uri = URI.create(storage.signPut("stories/staging/1/fixed-key", 60));
        assertEquals("localhost", uri.getHost());
        assertEquals(19000, uri.getPort());
        assertTrue(uri.getPath().endsWith("/stories/staging/1/fixed-key"));
        assertTrue(uri.getQuery().contains("X-Amz-Signature="));
    }

    @Test
    void ossV4SignsGetAndPutForConfiguredRegionAndKey() throws Exception {
        StorageProperties storageProperties = new StorageProperties();
        storageProperties.setProvider("oss");
        storageProperties.setEndpoint("https://oss-cn-hangzhou.aliyuncs.com");
        storageProperties.setPublicEndpoint(storageProperties.getEndpoint());
        storageProperties.setRegion("cn-hangzhou");
        storageProperties.setAccessKey("local-test-ak");
        storageProperties.setSecretKey("local-test-secret");
        OssObjectStorage storage = new OssObjectStorage(storageProperties);
        try {
            String put = storage.signPut("stories/staging/1/key", 60),
                    get = storage.signGet("stories/final/1/key", 60);
            assertTrue(put.contains("x-oss-signature="));
            assertTrue(get.contains("x-oss-signature="));
            assertTrue(put.contains("stories/staging/1/key"));
            assertTrue(get.contains("stories/final/1/key"));
        } finally {
            storage.close();
        }
    }
}
