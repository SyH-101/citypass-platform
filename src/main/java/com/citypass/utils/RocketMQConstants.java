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
    /** 超时关单消息 Tag（可靠任务到期后投递） */
    public static final String ORDER_TAG_TIMEOUT = "TIMEOUT";

    public static final String ORDER_PRODUCER_GROUP = "citypass-reservation-producer";
    public static final String ORDER_CONSUMER_GROUP = "citypass-reservation-consumer";

}
