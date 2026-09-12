package com.citypass.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class ActivitySearchItem {
    private Long activityId;
    private Long venueId;
    private String title;
    private String subTitle;
    private String description;
    private String activityCategory;
    private List<String> tags;
    private Integer passType;
    private LocalDateTime eventStartTime;
    private LocalDateTime eventEndTime;
    private Long priceCents;
    private String venueName;
    private String area;
    private String address;
    private Double longitude;
    private Double latitude;
    private Double distanceMeters;
}
