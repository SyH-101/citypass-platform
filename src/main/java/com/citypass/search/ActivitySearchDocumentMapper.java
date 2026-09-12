package com.citypass.search;

import org.springframework.stereotype.Component;

import java.time.ZoneId;
import java.util.Arrays;
import java.util.Collections;
import java.util.stream.Collectors;

@Component
public class ActivitySearchDocumentMapper {
    private final ZoneId zoneId = ZoneId.systemDefault();

    public ActivitySearchDocument from(ActivitySearchSource source) {
        ActivitySearchDocument document = new ActivitySearchDocument();
        document.setActivityId(source.getActivityId());
        document.setVenueId(source.getVenueId());
        document.setTitle(source.getTitle());
        document.setSubTitle(source.getSubTitle());
        document.setDescription(source.getDescription());
        document.setRules(source.getRules());
        document.setActivityCategory(source.getActivityCategory());
        document.setTags(splitTags(source.getTags()));
        document.setEventStartTime(toMillis(source.getEventStartTime()));
        document.setEventEndTime(toMillis(source.getEventEndTime()));
        document.setPriceCents(source.getPriceCents());
        document.setPassType(source.getPassType());
        document.setStatus(source.getStatus());
        document.setSearchVersion(source.getSearchVersion());
        document.setVenueName(source.getVenueName());
        document.setArea(source.getArea());
        document.setAddress(source.getAddress());
        document.setLongitude(source.getLongitude());
        document.setLatitude(source.getLatitude());
        document.setSearchable(isComplete(source));
        return document;
    }

    private boolean isComplete(ActivitySearchSource source) {
        return Integer.valueOf(1).equals(source.getStatus())
                && notBlank(source.getTitle())
                && notBlank(source.getActivityCategory())
                && source.getEventStartTime() != null
                && source.getEventEndTime() != null
                && source.getEventEndTime().isAfter(source.getEventStartTime());
    }

    private Long toMillis(java.time.LocalDateTime value) {
        return value == null ? null : value.atZone(zoneId).toInstant().toEpochMilli();
    }

    private java.util.List<String> splitTags(String tags) {
        if (!notBlank(tags)) return Collections.emptyList();
        return Arrays.stream(tags.split("[,，]"))
                .map(String::trim).filter(this::notBlank).distinct().limit(20).collect(Collectors.toList());
    }

    private boolean notBlank(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
