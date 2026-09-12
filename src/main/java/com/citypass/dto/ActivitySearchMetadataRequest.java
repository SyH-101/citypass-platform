package com.citypass.dto;

import lombok.Data;

import java.time.LocalDateTime;

/** Search-facing activity fields. Inventory, price and reservation windows are intentionally absent. */
@Data
public class ActivitySearchMetadataRequest {
    private String title;
    private String subTitle;
    private String description;
    private String activityCategory;
    private String tags;
    private LocalDateTime eventStartTime;
    private LocalDateTime eventEndTime;
}
