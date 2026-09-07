package com.citypass.mq;

import cn.hutool.json.JSONUtil;
import com.citypass.entity.ReservationOrder;
import com.citypass.utils.RocketMQConstants;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.exception.MQBrokerException;
import org.apache.rocketmq.client.exception.MQClientException;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.common.message.Message;
import org.apache.rocketmq.remoting.exception.RemotingException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;

/** 预约创建消息与超时检查消息发布器。 */
@Slf4j
@Component
@ConditionalOnProperty(name = "rocketmq.enabled", havingValue = "true")
public class RocketMQProducer implements OrderMessagePublisher {

    @Value("${rocketmq.name-server}")
    private String nameServer;

    private DefaultMQProducer producer;

    @PostConstruct
    public void init() throws MQClientException {
        producer = new DefaultMQProducer(RocketMQConstants.ORDER_PRODUCER_GROUP);
        producer.setNamesrvAddr(nameServer);
        producer.setRetryTimesWhenSendFailed(2);
        producer.setSendMsgTimeout(3000);
        producer.start();
        log.info("预约消息生产者启动成功，nameserver={}", nameServer);
    }

    @Override
    public SendResult sendOrderCreate(ReservationOrder order)
            throws MQClientException, MQBrokerException, RemotingException, InterruptedException {
        Message message = new Message(
                RocketMQConstants.ORDER_TOPIC,
                RocketMQConstants.ORDER_TAG_CREATE,
                JSONUtil.toJsonStr(order).getBytes(StandardCharsets.UTF_8));
        SendResult result = producer.send(message);
        log.debug("预约请求消息发送成功, requestId={}, msgId={}", order.getId(), result.getMsgId());
        return result;
    }

    @Override
    public void sendOrderTimeout(Long orderId)
            throws MQClientException, MQBrokerException, RemotingException, InterruptedException {
        Message message = new Message(
                RocketMQConstants.ORDER_TOPIC,
                RocketMQConstants.ORDER_TAG_TIMEOUT,
                orderId.toString().getBytes(StandardCharsets.UTF_8));
        message.setDelayTimeLevel(RocketMQConstants.ORDER_TIMEOUT_DELAY_LEVEL);
        producer.send(message);
        log.debug("预约超时消息发送成功, orderId={}, delayLevel={}",
                orderId, RocketMQConstants.ORDER_TIMEOUT_DELAY_LEVEL);
    }

    @PreDestroy
    public void destroy() {
        if (producer != null) {
            producer.shutdown();
        }
    }
}
