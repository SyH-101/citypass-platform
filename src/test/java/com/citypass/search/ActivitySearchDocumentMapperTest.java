package com.citypass.search;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ActivitySearchDocumentMapperTest {
    private final ActivitySearchDocumentMapper mapper = new ActivitySearchDocumentMapper();

    @Test
    void onlyCompleteListedActivityIsSearchable() {
        ActivitySearchSource source = completeSource();
        assertTrue(mapper.from(source).isSearchable());

        source.setStatus(2);
        assertFalse(mapper.from(source).isSearchable());
        source.setStatus(1);
        source.setEventStartTime(null);
        assertFalse(mapper.from(source).isSearchable());
    }

    @Test
    void normalizesChineseAndAsciiTagSeparators() {
        ActivitySearchSource source = completeSource();
        source.setTags("亲子，户外, 亲子");
        assertEquals(java.util.Arrays.asList("亲子", "户外"), mapper.from(source).getTags());
    }

    private ActivitySearchSource completeSource() {
        ActivitySearchSource source = new ActivitySearchSource();
        source.setActivityId(1L);
        source.setVenueId(1L);
        source.setTitle("城市音乐节");
        source.setActivityCategory("MUSIC");
        source.setEventStartTime(LocalDateTime.of(2030, 1, 1, 10, 0));
        source.setEventEndTime(LocalDateTime.of(2030, 1, 1, 12, 0));
        source.setStatus(1);
        source.setSearchVersion(1L);
        return source;
    }
}
