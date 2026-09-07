package com.citypass.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * <p>
 * 限量预约通行证表，与通行证是一对一关系
 * </p>
 *
 * @since 2022-01-04
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("tb_limited_pass_stock")
public class LimitedPassStock implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 关联的通行证的id
     */
    @TableId(value = "activity_pass_id", type = IdType.INPUT)
    private Long activityPassId;

    /**
     * 库存
     */
    private Integer stock;

    /**
     * 初始库存（对账账本：限量预约结束库存重算的基准，发布时与 stock 一致）
     */
    private Integer initialStock;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

    /**
     * 生效时间
     */
    private LocalDateTime beginTime;

    /**
     * 失效时间
     */
    private LocalDateTime endTime;

    /**
     * 更新时间
     */
    private LocalDateTime updateTime;


}
