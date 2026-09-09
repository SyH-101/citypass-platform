package com.citypass.mapper;

import com.citypass.entity.Venue;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * <p>
 *  Mapper 接口
 * </p>
 *
 * @since 2021-12-22
 */
public interface VenueMapper extends BaseMapper<Venue> {

    @Update("UPDATE tb_venue SET cache_version=cache_version+1 WHERE id=#{id}")
    int incrementCacheVersion(@Param("id") Long id);
}
