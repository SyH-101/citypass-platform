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

/**
 * <p>
 * 
 * </p>
 *
 * @since 2021-12-22
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("tb_activity_pass")
public class ActivityPass implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 主键
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 场馆id
     */
    private Long venueId;

    /**
     * 代金券标题
     */
    private String title;

    /**
     * 副标题
     */
    private String subTitle;

    /**
     * 使用规则
     */
    private String rules;

    /** 可检索的活动介绍，不复用票券使用规则。 */
    private String description;

    /** 活动分类，如 EXHIBITION / SPORT / FAMILY；不复用普通/限量票券 type。 */
    private String activityCategory;

    /** 逗号分隔的检索标签。 */
    private String tags;

    /** 活动实际举办时间，与限量预约开放窗口 beginTime/endTime 分离。 */
    private LocalDateTime eventStartTime;

    private LocalDateTime eventEndTime;

    /** 搜索文档专用单调版本，不与缓存、名额或任务租约版本混用。 */
    private Long searchVersion;

    /**
     * 支付金额
     */
    private Long payValue;

    /**
     * 抵扣金额
     */
    private Long actualValue;

    /**
     * 通行证类型
     */
    private Integer type;

    /**
     * 通行证类型
     */
    private Integer status;
    /**
     * 库存
     */
    @TableField(exist = false)
    private Integer stock;

    /**
     * 生效时间
     */
    @TableField(exist = false)
    private LocalDateTime beginTime;

    /**
     * 失效时间
     */
    @TableField(exist = false)
    private LocalDateTime endTime;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;


    /**
     * 更新时间
     */
    private LocalDateTime updateTime;


}
