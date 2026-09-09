package com.citypass.reliable;

import lombok.Data;

import java.time.LocalDateTime;

/** 数据库本地可靠任务。任务处理器必须幂等，以支持宕机后的重复执行。 */
@Data
public class ReliableTask {
    private Long id;
    private String taskType;
    private String payload;
    private Integer retryCount;
    private Integer maxRetry;
    private String lockedBy;
    private LocalDateTime leaseUntil;
    private Long version;
}
