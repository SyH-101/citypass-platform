package com.citypass.mq;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.citypass.utils.VenueCacheInvalidator;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.client.consumer.listener.MessageListenerConcurrently;
import org.apache.rocketmq.common.consumer.ConsumeFromWhere;
import org.apache.rocketmq.common.message.MessageExt;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.annotation.Resource;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

import static com.citypass.utils.RedisConstants.CACHE_VENUE_KEY;

/**
 * Canal binlog 缓存驱逐：MySQL 变更 → RocketMQ → 清除 Caffeine + Redis
 * 监听 tb_venue，配合 OpenResty L1 / Java 多级缓存做最终一致。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "canal.enabled", havingValue = "true")
public class CanalMQConsumer {

    public static final String CANAL_TOPIC = "canal-binlog-topic";

    @Value("${rocketmq.name-server}")
    private String nameServer;

    @Resource
    private VenueCacheInvalidator venueCacheInvalidator;

    private DefaultMQPushConsumer consumer;

    private final Map<String, Consumer<JSONObject>> tableHandlers = new HashMap<>();

    @PostConstruct
    public void init() throws Exception {
        tableHandlers.put("tb_venue", this::evictVenue);

        consumer = new DefaultMQPushConsumer("canal-cache-consumer-group");
        consumer.setNamesrvAddr(nameServer);
        consumer.setConsumeFromWhere(ConsumeFromWhere.CONSUME_FROM_FIRST_OFFSET);
        consumer.subscribe(CANAL_TOPIC, "*");
        consumer.registerMessageListener((MessageListenerConcurrently) (msgs, context) -> {
            for (MessageExt msg : msgs) {
                try {
                    handleBinlogEvent(new String(msg.getBody(), StandardCharsets.UTF_8));
                } catch (Exception e) {
                    log.error("binlog 事件处理失败, msgId={}", msg.getMsgId(), e);
                    return ConsumeConcurrentlyStatus.RECONSUME_LATER;
                }
            }
            return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
        });
        consumer.start();
        log.info("Canal 缓存驱逐消费者启动成功，监听 topic={}", CANAL_TOPIC);
    }

    private void handleBinlogEvent(String body) {
        JSONObject flat = JSONUtil.parseObj(body);
        String table = flat.getStr("table");
        Consumer<JSONObject> handler = tableHandlers.get(table);
        if (handler == null) {
            return;
        }
        JSONArray data = flat.getJSONArray("data");
        if (data == null || data.isEmpty()) {
            return;
        }
        for (Object row : data) {
            handler.accept((JSONObject) row);
        }
    }

    private void evictVenue(JSONObject row) {
        String id = row.getStr("id");
        if (id == null) {
            return;
        }
        venueCacheInvalidator.evict(id);
        log.info("[Canal] tb_venue 变更，已驱逐缓存 cache:venue:{}", id);
    }

    @PreDestroy
    public void destroy() {
        if (consumer != null) {
            consumer.shutdown();
        }
    }
}
