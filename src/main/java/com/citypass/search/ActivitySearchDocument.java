package com.citypass.search;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/** Elasticsearch projection. Date fields use epoch milliseconds to avoid timezone ambiguity. */
@Data
public class ActivitySearchDocument {
    private Long activityId;
    private Long venueId;
    private String title;
    private String subTitle;
    private String description;
    private String rules;
    private String activityCategory;
    private List<String> tags = new ArrayList<>();
    private Long eventStartTime;
    private Long eventEndTime;
    private Long priceCents;
    private Integer passType;
    private Integer status;
    private boolean searchable;
    private Long searchVersion;
    private String venueName;
    private String area;
    private String address;
    private Double longitude;
    private Double latitude;
}
