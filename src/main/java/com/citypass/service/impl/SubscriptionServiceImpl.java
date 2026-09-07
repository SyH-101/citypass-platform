package com.citypass.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.citypass.dto.Result;
import com.citypass.dto.UserDTO;
import com.citypass.entity.Subscription;
import com.citypass.mapper.SubscriptionMapper;
import com.citypass.service.ISubscriptionService;
import com.citypass.service.IUserService;
import com.citypass.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class SubscriptionServiceImpl extends ServiceImpl<SubscriptionMapper, Subscription>
        implements ISubscriptionService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private IUserService userService;

    @Override
    public Result subscribe(Long targetUserId, boolean subscribe) {
        Long userId = UserHolder.getUser().getId();
        if (targetUserId == null || userId.equals(targetUserId) || userService.getById(targetUserId) == null) {
            return Result.fail("订阅目标不存在或不可订阅");
        }
        String key = "subscriptions:" + userId;
        if (subscribe) {
            boolean exists = lambdaQuery()
                    .eq(Subscription::getUserId, userId)
                    .eq(Subscription::getTargetUserId, targetUserId)
                    .count() > 0;
            if (!exists) {
                Subscription subscription = new Subscription()
                        .setUserId(userId)
                        .setTargetUserId(targetUserId);
                if (!save(subscription)) {
                    return Result.fail("订阅失败");
                }
            }
            stringRedisTemplate.opsForSet().add(key, targetUserId.toString());
        } else {
            remove(new QueryWrapper<Subscription>()
                    .eq("user_id", userId)
                    .eq("target_user_id", targetUserId));
            stringRedisTemplate.opsForSet().remove(key, targetUserId.toString());
        }
        return Result.ok();
    }

    @Override
    public Result isSubscribed(Long targetUserId) {
        Long userId = UserHolder.getUser().getId();
        Integer count = query()
                .eq("user_id", userId)
                .eq("target_user_id", targetUserId)
                .count();
        return Result.ok(count > 0);
    }

    @Override
    public Result commonSubscriptions(Long otherUserId) {
        Long userId = UserHolder.getUser().getId();
        Set<String> intersect = stringRedisTemplate.opsForSet().intersect(
                "subscriptions:" + userId,
                "subscriptions:" + otherUserId);
        if (intersect == null || intersect.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }
        List<Long> ids = intersect.stream().map(Long::valueOf).collect(Collectors.toList());
        List<UserDTO> users = userService.listByIds(ids).stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());
        return Result.ok(users);
    }
}
