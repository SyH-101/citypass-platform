package com.citypass.utils;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

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
    private final RestTemplate restTemplate = new RestTemplate();

    @Value("${gateway-cache.purge-url:}")
    private String purgeUrl;

    public VenueCacheInvalidator(MultiLevelCacheService multiLevelCacheService,
                                 StringRedisTemplate redisTemplate) {
        this.multiLevelCacheService = multiLevelCacheService;
        this.redisTemplate = redisTemplate;
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
        purgeGateway(venueId);
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        String event = new String(message.getBody(), StandardCharsets.UTF_8);
        int separator = event.lastIndexOf(':');
        String venueId = separator < 0 ? event : event.substring(0, separator);
        multiLevelCacheService.evictLocal(CACHE_VENUE_KEY, venueId);
        log.debug("收到场馆缓存失效广播: {}", event);
    }

    private void purgeGateway(Object venueId) {
        if (purgeUrl == null || purgeUrl.trim().isEmpty()) return;
        try {
            String url = purgeUrl + "?id=" + URLEncoder.encode(
                    String.valueOf(venueId), StandardCharsets.UTF_8.name());
            restTemplate.exchange(url, HttpMethod.DELETE, null, String.class);
        } catch (Exception e) {
            throw new IllegalStateException("OpenResty 场馆缓存驱逐失败: " + venueId, e);
        }
    }
}
