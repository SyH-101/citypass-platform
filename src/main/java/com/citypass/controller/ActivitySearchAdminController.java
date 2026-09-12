package com.citypass.controller;

import com.citypass.dto.Result;
import com.citypass.search.ActivitySearchRebuildService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@RestController
@RequestMapping("/internal/activity-search")
public class ActivitySearchAdminController {
    private final ActivitySearchRebuildService rebuildService;

    @Value("${reliable-task.admin-token:}")
    private String adminToken;

    public ActivitySearchAdminController(ActivitySearchRebuildService rebuildService) {
        this.rebuildService = rebuildService;
    }

    @PostMapping("/rebuild")
    public Result rebuild(@RequestHeader(value = "X-Admin-Token", required = false) String supplied) {
        if (!authorized(supplied)) return Result.fail("运维接口未启用或凭证错误");
        return Result.ok(rebuildService.rebuild());
    }

    @GetMapping("/rebuild")
    public Result state(@RequestHeader(value = "X-Admin-Token", required = false) String supplied) {
        if (!authorized(supplied)) return Result.fail("运维接口未启用或凭证错误");
        return Result.ok(rebuildService.state());
    }

    @DeleteMapping("/indices/{index}")
    public Result deleteInactive(@PathVariable String index,
                                 @RequestHeader(value = "X-Admin-Token", required = false) String supplied) {
        if (!authorized(supplied)) return Result.fail("运维接口未启用或凭证错误");
        rebuildService.deleteInactiveIndex(index);
        return Result.ok();
    }

    private boolean authorized(String supplied) {
        if (adminToken == null || adminToken.isEmpty() || supplied == null) return false;
        return MessageDigest.isEqual(adminToken.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8));
    }
}
