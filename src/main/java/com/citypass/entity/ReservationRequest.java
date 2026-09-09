package com.citypass.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/** MySQL 中持久化的预约受理事实；Redis 仅保存它的短期查询投影。 */
@Data
@Accessors(chain = true)
@TableName("tb_reservation_request")
public class ReservationRequest implements Serializable {
    private static final long serialVersionUID = 1L;

    @TableId(value = "request_id", type = IdType.INPUT)
    private Long requestId;
    private Long userId;
    private Long activityPassId;
    private Boolean acceptWaitlist;
    private String status;
    private Long orderId;
    private String lastError;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
