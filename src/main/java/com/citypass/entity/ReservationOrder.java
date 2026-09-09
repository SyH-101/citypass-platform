package com.citypass.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/** 城市活动限量预约订单。 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("tb_reservation_order")
public class ReservationOrder implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 主键
     */
    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    /**
     * 下单的用户id
     */
    private Long userId;

    /**
     * 购买的代金券id
     */
    private Long activityPassId;

    /**
     * 支付方式 1：余额支付；2：支付宝；3：微信
     */
    private Integer payType;

    /**
     * 订单状态，1：未支付；2：已支付；3：已核销；4：已取消；5：退款中；6：已退款
     */
    private Integer status;

    /**
     * 下单时间
     */
    private LocalDateTime createTime;

    /**
     * 支付时间
     */
    private LocalDateTime payTime;

    /**
     * 核销时间
     */
    private LocalDateTime useTime;

    /**
     * 退款时间
     */
    private LocalDateTime refundTime;

    /**
     * 更新时间
     */
    private LocalDateTime updateTime;

    /** DIRECT：直接获得名额；WAITLIST：候补补位获得名额。 */
    private String source;

    /** 当前名额经历的补位轮次，避免无期限流转。 */
    private Integer promotionRound;

    /** 支付资格截止时间。 */
    private LocalDateTime offerExpireTime;

    /** 同一个实体名额每完成一次候补交接就递增，用于校验 Redis 归属。 */
    private Long resourceVersion;

    /** 请求库存不足时是否愿意进入候补，仅存在于 MQ 消息体。 */
    @TableField(exist = false)
    private Boolean acceptWaitlist;

}
