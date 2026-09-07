package com.citypass.config;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

/**
 * Caffeine 本地缓存（读写链路 Java 侧 L1）
 */
@Configuration
public class CaffeineConfig {

    /**
     * 场馆本地缓存：最大 10000 条，写入后 10 分钟过期
     */
    @Bean
    public Cache<String, Object> venueLocalCache() {
        return Caffeine.newBuilder()
                .maximumSize(10_000)
                .expireAfterWrite(10, TimeUnit.MINUTES)
                .recordStats()
                .build();
    }
}
