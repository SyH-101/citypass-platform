package com.citypass.search;

import com.citypass.config.ActivitySearchProperties;
import com.citypass.dto.ActivitySearchRequest;
import org.springframework.stereotype.Component;

import java.util.Locale;

@Component
public class ActivitySearchRequestValidator {
    private final ActivitySearchProperties properties;

    public ActivitySearchRequestValidator(ActivitySearchProperties properties) {
        this.properties = properties;
    }

    public NormalizedActivitySearchRequest validate(ActivitySearchRequest request) {
        if (request == null) request = new ActivitySearchRequest();
        NormalizedActivitySearchRequest normalized = new NormalizedActivitySearchRequest();
        normalized.setKeyword(trim(request.getKeyword()));
        String category = trim(request.getCategory());
        normalized.setCategory(category == null ? null : category.toUpperCase(Locale.ROOT));
        normalized.setEventFrom(request.getEventFrom());
        normalized.setEventTo(request.getEventTo());
        normalized.setMinPriceCents(request.getMinPriceCents());
        normalized.setMaxPriceCents(request.getMaxPriceCents());
        normalized.setLongitude(request.getLongitude());
        normalized.setLatitude(request.getLatitude());
        normalized.setRadiusMeters(request.getRadiusMeters());
        normalized.setCursor(trim(request.getCursor()));
        normalized.setSize(request.getSize() == null ? 20 : request.getSize());

        if (normalized.getKeyword() != null && normalized.getKeyword().length() > 100) {
            throw new IllegalArgumentException("关键词长度不能超过 100");
        }
        if (normalized.getCategory() != null && normalized.getCategory().length() > 32) {
            throw new IllegalArgumentException("活动分类长度不能超过 32");
        }
        if (normalized.getEventFrom() != null && normalized.getEventTo() != null
                && normalized.getEventFrom().isAfter(normalized.getEventTo())) {
            throw new IllegalArgumentException("举办时间范围无效");
        }
        if (negative(normalized.getMinPriceCents()) || negative(normalized.getMaxPriceCents())
                || normalized.getMinPriceCents() != null && normalized.getMaxPriceCents() != null
                && normalized.getMinPriceCents() > normalized.getMaxPriceCents()) {
            throw new IllegalArgumentException("价格范围无效，单位为分");
        }
        boolean hasLongitude = normalized.getLongitude() != null;
        boolean hasLatitude = normalized.getLatitude() != null;
        if (hasLongitude != hasLatitude) throw new IllegalArgumentException("经纬度必须同时提供");
        if (hasLongitude && (normalized.getLongitude() < -180 || normalized.getLongitude() > 180
                || normalized.getLatitude() < -90 || normalized.getLatitude() > 90)) {
            throw new IllegalArgumentException("经纬度超出合法范围");
        }
        if (normalized.getRadiusMeters() != null && (!hasLongitude || normalized.getRadiusMeters() <= 0
                || normalized.getRadiusMeters() > properties.getMaxRadiusMeters())) {
            throw new IllegalArgumentException("距离半径需要合法坐标且范围为 1-" + properties.getMaxRadiusMeters() + " 米");
        }
        if (normalized.getSize() <= 0 || normalized.getSize() > properties.getMaxPageSize()) {
            throw new IllegalArgumentException("页大小范围为 1-" + properties.getMaxPageSize());
        }

        ActivitySearchSort sort = parseSort(request.getSort());
        if (sort == null) sort = normalized.getKeyword() == null ? ActivitySearchSort.EVENT_TIME : ActivitySearchSort.RELEVANCE;
        if (sort == ActivitySearchSort.RELEVANCE && normalized.getKeyword() == null) {
            sort = ActivitySearchSort.EVENT_TIME;
        }
        if (sort == ActivitySearchSort.DISTANCE && !hasLongitude) {
            throw new IllegalArgumentException("距离排序必须提供经纬度");
        }
        normalized.setSort(sort);
        return normalized;
    }

    private ActivitySearchSort parseSort(String value) {
        if (value == null || value.trim().isEmpty()) return null;
        try {
            return ActivitySearchSort.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("排序只支持 RELEVANCE、EVENT_TIME、DISTANCE");
        }
    }

    private boolean negative(Long value) {
        return value != null && value < 0;
    }

    private String trim(String value) {
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }
}
