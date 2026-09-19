package com.citypass.utils;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withNoContent;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

class VenueCacheInvalidatorTest {

    @Test
    void everyConfiguredGatewayMustConfirmPurge() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        VenueCacheInvalidator invalidator = invalidator(restTemplate,
                "http://gateway-a/internal/cache/venue,http://gateway-b/internal/cache/venue", "");

        server.expect(once(), requestTo("http://gateway-a/internal/cache/venue?id=7&version=6"))
                .andExpect(method(HttpMethod.DELETE))
                .andRespond(withNoContent());
        server.expect(once(), requestTo("http://gateway-b/internal/cache/venue?id=7&version=6"))
                .andExpect(method(HttpMethod.DELETE))
                .andRespond(withNoContent());

        assertDoesNotThrow(() -> invalidator.purgeGateways(7L, 6L));
        server.verify();
    }

    @Test
    void oneFailedGatewayFailsWholeHandlerAfterTryingEveryEndpoint() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        VenueCacheInvalidator invalidator = invalidator(restTemplate,
                "http://gateway-a/internal/cache/venue,http://gateway-b/internal/cache/venue", "");

        server.expect(once(), requestTo("http://gateway-a/internal/cache/venue?id=7&version=6"))
                .andExpect(method(HttpMethod.DELETE))
                .andRespond(withNoContent());
        server.expect(once(), requestTo("http://gateway-b/internal/cache/venue?id=7&version=6"))
                .andExpect(method(HttpMethod.DELETE))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        IllegalStateException failure = assertThrows(
                IllegalStateException.class, () -> invalidator.purgeGateways(7L, 6L));
        assertTrue(failure.getMessage().contains("gateway-b"));
        server.verify();
    }

    @Test
    void legacySingleUrlIsMergedAndDeduplicated() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        String endpoint = "http://legacy-gateway/internal/cache/venue";
        VenueCacheInvalidator invalidator = invalidator(restTemplate, endpoint, endpoint);

        server.expect(once(), requestTo(endpoint + "?id=8&version=9"))
                .andExpect(method(HttpMethod.DELETE))
                .andRespond(withNoContent());

        assertDoesNotThrow(() -> invalidator.purgeGateways(8L, 9L));
        server.verify();
    }

    private VenueCacheInvalidator invalidator(RestTemplate restTemplate, String urls, String legacyUrl) {
        VenueCacheInvalidator invalidator = new VenueCacheInvalidator(
                mock(MultiLevelCacheService.class), mock(StringRedisTemplate.class), restTemplate);
        ReflectionTestUtils.setField(invalidator, "purgeUrls", urls);
        ReflectionTestUtils.setField(invalidator, "purgeUrl", legacyUrl);
        return invalidator;
    }
}
