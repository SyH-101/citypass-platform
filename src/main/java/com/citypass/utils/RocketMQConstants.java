package com.citypass.utils;

/**
 * RocketMQ 消息队列常量
 */
public class RocketMQConstants {

    public static final String NAME_SERVER = "127.0.0.1:9876";

    /** 城市活动预约 Topic */
    public static final String ORDER_TOPIC = "citypass-reservation-topic";
    /** 创建订单消息 Tag */
    public static final String ORDER_TAG_CREATE = "CREATE";
    /** 超时关单消息 Tag（延迟消息，到点触发） */
    public static final String ORDER_TAG_TIMEOUT = "TIMEOUT";

    public static final String ORDER_PRODUCER_GROUP = "citypass-reservation-producer";
    public static final String ORDER_CONSUMER_GROUP = "citypass-reservation-consumer";

    /** 延迟消息档位（broker.conf 自定义 messageDelayLevel 的第 15 档 = 15 分钟） */
    public static final int ORDER_TIMEOUT_DELAY_LEVEL = 15;
}
