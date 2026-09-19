package com.citypass.utils;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static com.citypass.utils.RedisConstants.CACHE_VENUE_BASELINE_KEY;
import static com.citypass.utils.RedisConstants.CACHE_VENUE_INVALIDATION_CHANNEL;
import static com.citypass.utils.RedisConstants.CACHE_VENUE_KEY;
import static com.citypass.utils.RedisConstants.CACHE_VENUE_VERSION_KEY;

/** Versioned Redis invalidation plus pub/sub fan-out to every JVM's Caffeine cache. */
@Slf4j
@Component
public class VenueCacheInvalidator implements MessageListener {
    private static final DefaultRedisScript<Long> INVALIDATE_SCRIPT = new DefaultRedisScript<>();

    static {
        INVALIDATE_SCRIPT.setLocation(new ClassPathResource("invalidate-venue-cache.lua"));
        INVALIDATE_SCRIPT.setResultType(Long.class);
    }

    private final MultiLevelCacheService multiLevelCacheService;
    private final StringRedisTemplate redisTemplate;
    private final RestTemplate restTemplate;

    @Value("${gateway-cache.purge-url:}")
    private String purgeUrl;

    @Value("${gateway-cache.purge-urls:}")
    private String purgeUrls;

    @Autowired
    public VenueCacheInvalidator(MultiLevelCacheService multiLevelCacheService,
                                 StringRedisTemplate redisTemplate) {
        this(multiLevelCacheService, redisTemplate, newGatewayRestTemplate());
    }

    VenueCacheInvalidator(MultiLevelCacheService multiLevelCacheService,
                          StringRedisTemplate redisTemplate,
                          RestTemplate restTemplate) {
        this.multiLevelCacheService = multiLevelCacheService;
        this.redisTemplate = redisTemplate;
        this.restTemplate = restTemplate;
    }

    private static RestTemplate newGatewayRestTemplate() {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(1000);
        requestFactory.setReadTimeout(2000);
        return new RestTemplate(requestFactory);
    }

    /** For Canal/delete events without a committed row version, atomically advance from the Redis version. */
    public void evict(Object venueId) {
        evict(venueId, -1L);
    }

    public void evict(Object venueId, Long committedVersion) {
        Long version = redisTemplate.execute(
                INVALIDATE_SCRIPT,
                Arrays.asList(CACHE_VENUE_KEY + venueId,
                        CACHE_VENUE_BASELINE_KEY + venueId,
                        CACHE_VENUE_VERSION_KEY + venueId),
                CACHE_VENUE_INVALIDATION_CHANNEL,
                String.valueOf(venueId),
                String.valueOf(committedVersion == null ? -1L : committedVersion));
        if (version == null) throw new IllegalStateException("场馆缓存失效脚本返回空");
        // 发布端也立即清理，避免依赖自身能否收到 pub/sub 回环消息。
        multiLevelCacheService.evictLocal(CACHE_VENUE_KEY, venueId);
        purgeGateways(venueId, version);
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        String event = new String(message.getBody(), StandardCharsets.UTF_8);
        int separator = event.lastIndexOf(':');
        String venueId = separator < 0 ? event : event.substring(0, separator);
        multiLevelCacheService.evictLocal(CACHE_VENUE_KEY, venueId);
        log.debug("收到场馆缓存失效广播: {}", event);
    }

    void purgeGateways(Object venueId, long committedVersion) {
        List<String> endpoints = configuredPurgeUrls();
        if (endpoints.isEmpty()) return;

        List<String> failedEndpoints = new ArrayList<>();
        Throwable firstFailure = null;
        for (String endpoint : endpoints) {
            URI uri = UriComponentsBuilder.fromHttpUrl(endpoint)
                    .queryParam("id", String.valueOf(venueId))
                    .queryParam("version", committedVersion)
                    .build()
                    .encode(StandardCharsets.UTF_8)
                    .toUri();
            try {
                ResponseEntity<Void> response = restTemplate.exchange(uri, HttpMethod.DELETE, null, Void.class);
                if (!response.getStatusCode().is2xxSuccessful()) {
                    throw new IllegalStateException("HTTP " + response.getStatusCodeValue());
                }
                log.info("OpenResty 场馆缓存驱逐成功: endpoint={}, venueId={}, version={}",
                        endpoint, venueId, committedVersion);
            } catch (Exception e) {
                failedEndpoints.add(endpoint);
                if (firstFailure == null) firstFailure = e;
                log.warn("OpenResty 场馆缓存驱逐失败: endpoint={}, venueId={}, version={}",
                        endpoint, venueId, committedVersion, e);
            }
        }
        if (!failedEndpoints.isEmpty()) {
            throw new IllegalStateException(
                    "OpenResty 场馆缓存驱逐失败: venueId=" + venueId
                            + ", version=" + committedVersion
                            + ", endpoints=" + failedEndpoints,
                    firstFailure);
        }
    }

    private List<String> configuredPurgeUrls() {
        Set<String> endpoints = new LinkedHashSet<>();
        addConfiguredUrls(endpoints, purgeUrls);
        addConfiguredUrls(endpoints, purgeUrl);
        return new ArrayList<>(endpoints);
    }

    private void addConfiguredUrls(Set<String> endpoints, String configured) {
        if (configured == null) return;
        for (String candidate : configured.split("[,;\\r\\n]+")) {
            String endpoint = candidate.trim();
            if (!endpoint.isEmpty()) endpoints.add(endpoint);
        }
    }
}
