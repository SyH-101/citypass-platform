package com.hmdp.mq;

import com.hmdp.entity.VoucherOrder;
import org.apache.rocketmq.client.producer.SendResult;

/** 订单消息发布端口，使非秒杀功能可在未启动 MQ 时独立运行。 */
public interface OrderMessagePublisher {
    SendResult sendOrderCreateInTransaction(VoucherOrder order, SeckillTxContext context) throws Exception;
    SendResult sendOrderCreate(VoucherOrder order) throws Exception;
    void sendOrderTimeout(Long orderId) throws Exception;
}
