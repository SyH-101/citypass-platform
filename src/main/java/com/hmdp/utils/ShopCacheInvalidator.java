package com.hmdp.utils;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.client.RestTemplate;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_KEY;

/** 统一驱逐 Java 两级缓存和可选的 OpenResty 网关缓存。 */
@Slf4j
@Component
public class ShopCacheInvalidator {

    private final MultiLevelCacheService multiLevelCacheService;
    private final RestTemplate restTemplate = new RestTemplate();

    @Value("${gateway-cache.purge-url:}")
    private String purgeUrl;

    public ShopCacheInvalidator(MultiLevelCacheService multiLevelCacheService) {
        this.multiLevelCacheService = multiLevelCacheService;
    }

    public void evictAfterCommit(Long shopId) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    evict(shopId);
                }
            });
            return;
        }
        evict(shopId);
    }

    public void evict(Object shopId) {
        multiLevelCacheService.evict(CACHE_SHOP_KEY, shopId);
        if (purgeUrl == null || purgeUrl.trim().isEmpty()) {
            return;
        }
        try {
            String url = purgeUrl + "?id=" + URLEncoder.encode(String.valueOf(shopId), StandardCharsets.UTF_8.name());
            restTemplate.exchange(url, HttpMethod.DELETE, null, String.class);
        } catch (Exception e) {
            // 网关缓存本身有短 TTL，驱逐失败只影响短时间一致性，Canal/下次写入还会重试驱逐。
            log.warn("OpenResty 商铺缓存驱逐失败, shopId={}", shopId, e);
        }
    }
}
