package com.citypass.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.citypass.entity.ReservationWaitlist;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface ReservationWaitlistMapper extends BaseMapper<ReservationWaitlist> {

    /** 多笔订单同时释放名额时，每个事务会跳过已被锁定的候选人。 */
    @Select("SELECT * FROM tb_reservation_waitlist " +
            "WHERE activity_pass_id=#{activityPassId} AND status='WAITING' " +
            "ORDER BY request_id LIMIT 1 FOR UPDATE SKIP LOCKED")
    ReservationWaitlist selectNextForUpdate(@Param("activityPassId") Long activityPassId);

    @Select("SELECT COUNT(*) FROM tb_reservation_waitlist " +
            "WHERE activity_pass_id=#{activityPassId} AND status='WAITING' AND request_id < #{requestId}")
    int countWaitingAhead(@Param("activityPassId") Long activityPassId,
                          @Param("requestId") Long requestId);
}
