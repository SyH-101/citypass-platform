package com.citypass.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.citypass.entity.ReservationWaitlist;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface ReservationWaitlistMapper extends BaseMapper<ReservationWaitlist> {

    /** 阻塞锁定真正队首，不跳过正在处理的较早候补。 */
    @Select("SELECT * FROM tb_reservation_waitlist " +
            "WHERE activity_pass_id=#{activityPassId} AND status='WAITING' " +
            "ORDER BY request_id LIMIT 1 FOR UPDATE")
    ReservationWaitlist selectNextForUpdate(@Param("activityPassId") Long activityPassId);

    @Select("SELECT COUNT(*) FROM tb_reservation_waitlist " +
            "WHERE activity_pass_id=#{activityPassId} AND status='WAITING' AND request_id < #{requestId}")
    int countWaitingAhead(@Param("activityPassId") Long activityPassId,
                          @Param("requestId") Long requestId);
}
