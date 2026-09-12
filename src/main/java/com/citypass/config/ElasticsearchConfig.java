package com.citypass.config;

import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.client.RestHighLevelClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Arrays;

@Configuration
@ConditionalOnProperty(name = "search.enabled", havingValue = "true")
public class ElasticsearchConfig {
    @Bean(destroyMethod = "close")
    public RestHighLevelClient activitySearchClient(ActivitySearchProperties properties) {
        HttpHost[] hosts = Arrays.stream(properties.getHosts().split(","))
                .map(String::trim).filter(value -> !value.isEmpty()).map(HttpHost::create).toArray(HttpHost[]::new);
        if (hosts.length == 0) throw new IllegalStateException("search.hosts 不能为空");
        return new RestHighLevelClient(RestClient.builder(hosts)
                .setRequestConfigCallback(builder -> builder
                        .setConnectTimeout(properties.getConnectTimeoutMs())
                        .setSocketTimeout(properties.getSocketTimeoutMs())
                        .setConnectionRequestTimeout(properties.getRequestTimeoutMs())));
    }
}
