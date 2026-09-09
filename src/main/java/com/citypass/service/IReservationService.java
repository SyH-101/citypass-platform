package com.citypass.service;

import com.citypass.dto.Result;
import com.citypass.entity.ReservationOrder;
import com.baomidou.mybatisplus.extension.service.IService;

/** 限量预约、候补和支付服务。 */
public interface IReservationService extends IService<ReservationOrder> {

    Result reserve(Long activityPassId, boolean acceptWaitlist);

    Result payOrder(Long orderId);

    Result cancelOrder(Long orderId);

    Result cancelWaitlist(Long requestId);

    /** 仅在 offer_expire_time 已到期时关闭未支付订单，并优先把名额补给候补用户。 */
    void cancelTimeoutOrder(Long orderId);

    /** MQ 异步处理预约请求（创建订单或加入候补，支持重复投递）。 */
    void createOrderFromMQ(ReservationOrder reservationOrder);

    /**
     * 查询预约请求结果。
     * @return PROCESSING / RESERVED / WAITLISTED / PAID / CANCELLED / FAIL_*
     */
    Result getReservationResult(Long requestId);
}
