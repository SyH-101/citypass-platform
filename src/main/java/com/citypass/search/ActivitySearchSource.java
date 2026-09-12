package com.citypass.search;

import lombok.Data;

import java.time.LocalDateTime;

/** One statement-consistent MySQL snapshot used to build one activity document. */
@Data
public class ActivitySearchSource {
    private Long activityId;
    private Long venueId;
    private String title;
    private String subTitle;
    private String description;
    private String rules;
    private String activityCategory;
    private String tags;
    private LocalDateTime eventStartTime;
    private LocalDateTime eventEndTime;
    private Long priceCents;
    private Integer passType;
    private Integer status;
    private Long searchVersion;
    private String venueName;
    private String area;
    private String address;
    private Double longitude;
    private Double latitude;
}
