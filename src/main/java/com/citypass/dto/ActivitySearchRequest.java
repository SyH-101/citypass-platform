package com.citypass.dto;

import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDateTime;

@Data
public class ActivitySearchRequest {
    private String keyword;
    private String category;
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    private LocalDateTime eventFrom;
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    private LocalDateTime eventTo;
    private Long minPriceCents;
    private Long maxPriceCents;
    private Double longitude;
    private Double latitude;
    private Integer radiusMeters;
    private String sort;
    private Integer size;
    private String cursor;
}
