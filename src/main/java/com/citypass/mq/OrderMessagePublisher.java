package com.citypass.mq;

import com.citypass.entity.ReservationOrder;
import org.apache.rocketmq.client.producer.SendResult;

/** 订单消息发布端口，使非限量预约功能可在未启动 MQ 时独立运行。 */
public interface OrderMessagePublisher {
    SendResult sendOrderCreate(ReservationOrder order) throws Exception;
    SendResult sendOrderTimeout(Long orderId) throws Exception;
}
