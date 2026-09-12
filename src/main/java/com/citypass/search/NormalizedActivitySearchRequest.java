package com.citypass.search;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class NormalizedActivitySearchRequest {
    private String keyword;
    private String category;
    private LocalDateTime eventFrom;
    private LocalDateTime eventTo;
    private Long minPriceCents;
    private Long maxPriceCents;
    private Double longitude;
    private Double latitude;
    private Integer radiusMeters;
    private ActivitySearchSort sort;
    private int size;
    private String cursor;
}
