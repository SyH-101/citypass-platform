package com.citypass.utils;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class RedisData {
    private LocalDateTime expireTime;
    private Object data;
    private Long version;
    private Boolean nullValue;
}
