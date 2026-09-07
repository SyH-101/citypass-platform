package com.citypass.service;

import com.citypass.dto.Result;
import com.citypass.entity.ActivityPass;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @since 2021-12-22
 */
public interface IActivityPassService extends IService<ActivityPass> {

    Result queryActivityPassOfVenue(Long venueId);

    void addLimitedPassStock(ActivityPass pass);

    void addActivityPass(ActivityPass pass);
}
