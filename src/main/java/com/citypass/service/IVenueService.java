package com.citypass.service;

import com.citypass.dto.Result;
import com.citypass.entity.Venue;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @since 2021-12-22
 */
public interface IVenueService extends IService<Venue> {

    Result queryById(Long id);

    /** 压测基线：Redis → MySQL。 */
    Result queryByIdRedisBaseline(Long id);

    Result update(Venue venue);

    Result queryVenueByType(Integer categoryId, Integer current, Double x, Double y);
}
