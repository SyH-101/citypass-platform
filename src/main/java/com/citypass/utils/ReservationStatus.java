package com.citypass.utils;

/** 预约请求、候补记录与订单来源常量。 */
public final class ReservationStatus {

    private ReservationStatus() {
    }

    public static final String PROCESSING = "PROCESSING";
    public static final String RESERVED = "RESERVED";
    public static final String WAITLISTED = "WAITLISTED";
    public static final String PAID = "PAID";
    public static final String CANCELLED = "CANCELLED";
    public static final String FAIL_STOCK = "FAIL_STOCK";
    public static final String FAIL_REPEAT = "FAIL_REPEAT";
    public static final String FAIL_SYSTEM = "FAIL_SYSTEM";
    public static final String FAIL_NOT_STARTED = "FAIL_NOT_STARTED";
    public static final String FAIL_ENDED = "FAIL_ENDED";
    public static final String FAIL_UNAVAILABLE = "FAIL_UNAVAILABLE";

    public static final String WAITING = "WAITING";
    public static final String OFFERED = "OFFERED";
    public static final String ACCEPTED = "ACCEPTED";
    public static final String EXPIRED = "EXPIRED";

    public static final String SOURCE_DIRECT = "DIRECT";
    public static final String SOURCE_WAITLIST = "WAITLIST";
}
