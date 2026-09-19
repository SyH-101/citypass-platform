package com.citypass.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RedissonConfig {

    @Value("${spring.redis.host:127.0.0.1}")
    private String redisHost;

    @Value("${spring.redis.port:6379}")
    private String redisPort;

    @Value("${spring.redis.password:}")
    private String redisPassword;

    @Value("${cache.reliability.redis-command-timeout-ms:1000}")
    private int redisCommandTimeoutMs;

    @Bean
    public RedissonClient redissonClient() {
        Config config = new Config();
        String address = "redis://" + redisHost + ":" + redisPort;
        org.redisson.config.SingleServerConfig server = config.useSingleServer()
                .setAddress(address)
                .setConnectTimeout(Math.max(100, redisCommandTimeoutMs))
                .setTimeout(Math.max(100, redisCommandTimeoutMs));
        if (redisPassword != null && !redisPassword.isEmpty()) {
            server.setPassword(redisPassword);
        }
        return Redisson.create(config);
    }
}
