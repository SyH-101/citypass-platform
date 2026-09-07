package com.citypass.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.citypass.entity.ActivityPass;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * <p>
 *  Mapper 接口
 * </p>
 *
 * @since 2021-12-22
 */
public interface ActivityPassMapper extends BaseMapper<ActivityPass> {

    List<ActivityPass> queryActivityPassOfVenue(@Param("venueId") Long venueId);
}
