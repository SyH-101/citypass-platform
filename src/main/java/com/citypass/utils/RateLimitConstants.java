package com.citypass.utils;

/**
 * 二级限流相关 Redis Key
 */
public class RateLimitConstants {

    private RateLimitConstants() {
    }

    /** 业务滑动窗口：rate:sw:reservation:{userId} */
    public static final String SLIDING_WINDOW_RESERVATION_KEY = "rate:sw:reservation:";
}
