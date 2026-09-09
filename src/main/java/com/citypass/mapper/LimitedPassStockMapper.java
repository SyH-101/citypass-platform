package com.citypass.mapper;

import com.citypass.entity.LimitedPassStock;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * <p>
 * 限量预约通行证表，与通行证是一对一关系 Mapper 接口
 * </p>
 *
 * @since 2022-01-04
 */
public interface LimitedPassStockMapper extends BaseMapper<LimitedPassStock> {

    @Select("SELECT * FROM tb_limited_pass_stock WHERE activity_pass_id=#{activityPassId} FOR UPDATE")
    LimitedPassStock selectByIdForUpdate(@Param("activityPassId") Long activityPassId);
}
