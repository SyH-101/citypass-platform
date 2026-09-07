package com.citypass.service;

import com.citypass.dto.Result;
import com.citypass.entity.Subscription;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @since 2021-12-22
 */
public interface ISubscriptionService extends IService<Subscription> {

    Result subscribe(Long targetUserId, boolean subscribe);

    Result isSubscribed(Long targetUserId);

    Result commonSubscriptions(Long userId);
}
