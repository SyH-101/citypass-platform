package com.hmdp.mq;

import com.hmdp.entity.VoucherOrder;
import org.apache.rocketmq.client.producer.SendResult;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** 本地只调试读接口时的明确降级；调用秒杀会返回“MQ 未启用”，不会在启动阶段连接失败。 */
@Component
@ConditionalOnProperty(name = "rocketmq.enabled", havingValue = "false", matchIfMissing = true)
public class DisabledOrderMessagePublisher implements OrderMessagePublisher {
    private IllegalStateException disabled() {
        return new IllegalStateException("RocketMQ 未启用，请设置 rocketmq.enabled=true");
    }

    @Override
    public SendResult sendOrderCreateInTransaction(VoucherOrder order, SeckillTxContext context) {
        throw disabled();
    }

    @Override
    public SendResult sendOrderCreate(VoucherOrder order) {
        throw disabled();
    }

    @Override
    public void sendOrderTimeout(Long orderId) {
        throw disabled();
    }
}
