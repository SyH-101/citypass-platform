package com.citypass.storage;

import org.junit.jupiter.api.Test;
import java.net.URI;
import static org.junit.jupiter.api.Assertions.*;

class StorageSigningTest {
    @Test void minioSignsActualPublicHostAndFixedKeyWithoutNetworking() throws Exception {
        StorageProperties p=new StorageProperties();
        p.setEndpoint("http://minio:9000"); p.setPublicEndpoint("http://localhost:19000");
        MinioObjectStorage storage=new MinioObjectStorage(p);
        URI uri=URI.create(storage.signPut("stories/staging/1/fixed-key",60));
        assertEquals("localhost",uri.getHost()); assertEquals(19000,uri.getPort());
        assertTrue(uri.getPath().endsWith("/stories/staging/1/fixed-key"));
        assertTrue(uri.getQuery().contains("X-Amz-Signature="));
    }
    @Test void ossV4SignsGetAndPutForConfiguredRegionAndKey() throws Exception {
        StorageProperties p=new StorageProperties(); p.setProvider("oss");
        p.setEndpoint("https://oss-cn-hangzhou.aliyuncs.com"); p.setPublicEndpoint(p.getEndpoint());
        p.setRegion("cn-hangzhou"); p.setAccessKey("local-test-ak"); p.setSecretKey("local-test-secret");
        OssObjectStorage storage=new OssObjectStorage(p);
        try {
            String put=storage.signPut("stories/staging/1/key",60),get=storage.signGet("stories/final/1/key",60);
            assertTrue(put.contains("x-oss-signature=")); assertTrue(get.contains("x-oss-signature="));
            assertTrue(put.contains("stories/staging/1/key")); assertTrue(get.contains("stories/final/1/key"));
        } finally { storage.close(); }
    }
}
