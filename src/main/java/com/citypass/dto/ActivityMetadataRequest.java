package com.citypass.dto;

import lombok.Data;
import java.time.LocalDateTime;

/**
 * Activity activity fields. Inventory, price and reservation windows are intentionally absent.
 */
@Data
public class ActivityMetadataRequest {

    private String title;
    private String subTitle;
    private String description;
    private String activityCategory;
    private String tags;
    private LocalDateTime eventStartTime;
    private LocalDateTime eventEndTime;
}
