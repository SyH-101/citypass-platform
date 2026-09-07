package com.citypass.controller;


import com.citypass.dto.Result;
import com.citypass.service.ISubscriptionService;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;

/**
 * <p>
 * 前端控制器
 * </p>
 *
 */
@RestController
@RequestMapping("/subscriptions")
public class SubscriptionController {

    @Resource
    private ISubscriptionService subscriptionService;

    @PutMapping("/{targetUserId}")
    public Result subscribe(@PathVariable Long targetUserId) {
        return subscriptionService.subscribe(targetUserId, true);
    }

    @DeleteMapping("/{targetUserId}")
    public Result unsubscribe(@PathVariable Long targetUserId) {
        return subscriptionService.subscribe(targetUserId, false);
    }

    @GetMapping("/{targetUserId}")
    public Result isSubscribed(@PathVariable Long targetUserId) {
        return subscriptionService.isSubscribed(targetUserId);
    }

    @GetMapping("/common/{userId}")
    public Result commonSubscriptions(@PathVariable Long userId){
        return subscriptionService.commonSubscriptions(userId);
    }
}
