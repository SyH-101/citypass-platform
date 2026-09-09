package com.citypass.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/** 限量活动候补记录。MySQL 记录是候补顺序与状态的最终依据。 */
@Data
@Accessors(chain = true)
@TableName("tb_reservation_waitlist")
public class ReservationWaitlist implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 数据库主键；排队顺序使用入口生成的单调 requestId。 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 用户首次预约时获得的请求号；补位成功后也作为订单号。 */
    private Long requestId;
    private Long activityPassId;
    private Long userId;
    private String status;
    private Long offeredOrderId;
    private LocalDateTime offerExpireTime;
    private LocalDateTime waitExpireTime;
    private String invalidReason;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
