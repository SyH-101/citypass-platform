package com.citypass.search;

import com.citypass.config.ActivitySearchProperties;
import com.citypass.dto.ActivitySearchRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ActivitySearchRequestValidatorTest {
    private ActivitySearchRequestValidator validator;

    @BeforeEach
    void setUp() {
        ActivitySearchProperties properties = new ActivitySearchProperties();
        properties.setMaxPageSize(50);
        properties.setMaxRadiusMeters(50000);
        validator = new ActivitySearchRequestValidator(properties);
    }

    @Test
    void choosesDeterministicDefaults() {
        NormalizedActivitySearchRequest empty = validator.validate(new ActivitySearchRequest());
        assertEquals(ActivitySearchSort.EVENT_TIME, empty.getSort());
        assertEquals(20, empty.getSize());

        ActivitySearchRequest keyword = new ActivitySearchRequest();
        keyword.setKeyword("城市音乐节");
        assertEquals(ActivitySearchSort.RELEVANCE, validator.validate(keyword).getSort());
    }

    @Test
    void validatesDistanceCoordinatesAndLimits() {
        ActivitySearchRequest missingCoordinates = new ActivitySearchRequest();
        missingCoordinates.setSort("distance");
        assertThrows(IllegalArgumentException.class, () -> validator.validate(missingCoordinates));

        ActivitySearchRequest invalidLatitude = new ActivitySearchRequest();
        invalidLatitude.setLongitude(120.1);
        invalidLatitude.setLatitude(91.0);
        assertThrows(IllegalArgumentException.class, () -> validator.validate(invalidLatitude));

        ActivitySearchRequest tooLarge = new ActivitySearchRequest();
        tooLarge.setLongitude(120.1);
        tooLarge.setLatitude(30.2);
        tooLarge.setRadiusMeters(50001);
        assertThrows(IllegalArgumentException.class, () -> validator.validate(tooLarge));
    }

    @Test
    void rejectsInvalidRanges() {
        ActivitySearchRequest time = new ActivitySearchRequest();
        time.setEventFrom(LocalDateTime.of(2030, 5, 2, 10, 0));
        time.setEventTo(LocalDateTime.of(2030, 5, 1, 10, 0));
        assertThrows(IllegalArgumentException.class, () -> validator.validate(time));

        ActivitySearchRequest price = new ActivitySearchRequest();
        price.setMinPriceCents(200L);
        price.setMaxPriceCents(100L);
        assertThrows(IllegalArgumentException.class, () -> validator.validate(price));

        ActivitySearchRequest size = new ActivitySearchRequest();
        size.setSize(51);
        assertThrows(IllegalArgumentException.class, () -> validator.validate(size));
    }
}
