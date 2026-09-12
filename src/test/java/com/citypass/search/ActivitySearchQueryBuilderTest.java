package com.citypass.search;

import com.citypass.config.ActivitySearchProperties;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ActivitySearchQueryBuilderTest {
    @Test
    void buildsWeightedFullTextHardFiltersAndStableSort() {
        ActivitySearchProperties properties = new ActivitySearchProperties();
        ActivitySearchService service = new ActivitySearchService(properties, null, null, null, null, null);
        NormalizedActivitySearchRequest request = new NormalizedActivitySearchRequest();
        request.setKeyword("城市音乐节");
        request.setCategory("MUSIC");
        request.setEventFrom(LocalDateTime.of(2030, 1, 1, 10, 0));
        request.setEventTo(LocalDateTime.of(2030, 1, 2, 10, 0));
        request.setMinPriceCents(100L);
        request.setMaxPriceCents(5000L);
        request.setSort(ActivitySearchSort.RELEVANCE);
        request.setSize(10);

        String json = service.buildQuery(request, 123L, null, "pit").toString();
        assertTrue(json.contains("title^4.0"), json);
        assertTrue(json.contains("description"), json);
        assertTrue(json.contains("activityCategory"));
        assertTrue(json.contains("eventEndTime"));
        assertTrue(json.contains("eventStartTime"));
        assertTrue(json.contains("priceCents"));
        assertTrue(json.contains("_score"));
        assertTrue(json.contains("activityId"));
    }
}
