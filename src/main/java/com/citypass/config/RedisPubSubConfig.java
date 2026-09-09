package com.citypass.config;

import com.citypass.utils.VenueCacheInvalidator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import static com.citypass.utils.RedisConstants.CACHE_VENUE_INVALIDATION_CHANNEL;

@Configuration
public class RedisPubSubConfig {
    @Bean
    public RedisMessageListenerContainer cacheInvalidationContainer(
            RedisConnectionFactory connectionFactory,
            VenueCacheInvalidator invalidator) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(invalidator, new ChannelTopic(CACHE_VENUE_INVALIDATION_CHANNEL));
        return container;
    }
}
