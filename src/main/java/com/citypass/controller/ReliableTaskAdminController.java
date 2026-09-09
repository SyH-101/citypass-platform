package com.citypass.controller;

import com.citypass.dto.Result;
import com.citypass.reliable.ReliableTaskRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;

/** Minimal operations endpoint for dead-letter inspection and explicit replay. Disabled while token is blank. */
@RestController
@RequestMapping("/internal/reliable-tasks")
public class ReliableTaskAdminController {
    private final ReliableTaskRepository repository;

    @Value("${reliable-task.admin-token:}")
    private String adminToken;

    public ReliableTaskAdminController(ReliableTaskRepository repository) {
        this.repository = repository;
    }

    @GetMapping("/stats")
    public Result stats(@RequestHeader(value = "X-Admin-Token", required = false) String supplied) {
        if (!authorized(supplied)) return Result.fail("运维接口未启用或凭证错误");
        Map<String, Long> stats = new LinkedHashMap<>();
        stats.put("pending", repository.countByStatus("PENDING"));
        stats.put("running", repository.countByStatus("RUNNING"));
        stats.put("ready", repository.countReady());
        stats.put("dead", repository.countByStatus("DEAD"));
        stats.put("oldestReadySeconds", repository.oldestReadyAgeSeconds());
        return Result.ok(stats);
    }

    @PostMapping("/{id}/replay")
    public Result replay(@PathVariable Long id,
                         @RequestHeader(value = "X-Admin-Token", required = false) String supplied) {
        if (!authorized(supplied)) return Result.fail("运维接口未启用或凭证错误");
        return repository.replayDead(id) ? Result.ok("已重新入队") : Result.fail("任务不存在或不在 DEAD 状态");
    }

    private boolean authorized(String supplied) {
        if (adminToken == null || adminToken.isEmpty() || supplied == null) return false;
        return MessageDigest.isEqual(adminToken.getBytes(StandardCharsets.UTF_8),
                supplied.getBytes(StandardCharsets.UTF_8));
    }
}
