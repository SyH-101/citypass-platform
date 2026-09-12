package com.citypass.search;

import com.citypass.config.ActivitySearchProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ActivitySearchCursorCodecTest {
    private ActivitySearchCursorCodec codec;

    @BeforeEach
    void setUp() {
        ActivitySearchProperties properties = new ActivitySearchProperties();
        properties.setCursorSecret("0123456789abcdef-test-secret");
        codec = new ActivitySearchCursorCodec(properties);
    }

    @Test
    void signedCursorRoundTripsAndRejectsTampering() {
        ActivitySearchCursor cursor = new ActivitySearchCursor();
        cursor.setPitId("pit-id");
        cursor.setSnapshotEpochMillis(123L);
        cursor.setExpiresAtEpochMillis(456L);
        cursor.setQueryFingerprint("fingerprint");
        cursor.setSortValues(Arrays.asList(1.5, 100L));
        String encoded = codec.encode(cursor);
        ActivitySearchCursor decoded = codec.decode(encoded);
        assertEquals("pit-id", decoded.getPitId());
        assertEquals(2, decoded.getSortValues().size());

        char replacement = encoded.charAt(4) == 'A' ? 'B' : 'A';
        String tampered = encoded.substring(0, 4) + replacement + encoded.substring(5);
        assertThrows(IllegalArgumentException.class, () -> codec.decode(tampered));
    }

    @Test
    void fingerprintBindsSortSizeAndFrozenTime() {
        NormalizedActivitySearchRequest request = new NormalizedActivitySearchRequest();
        request.setKeyword("音乐");
        request.setSort(ActivitySearchSort.RELEVANCE);
        request.setSize(10);
        String first = codec.fingerprint(request, 100L);
        request.setSize(20);
        assertNotEquals(first, codec.fingerprint(request, 100L));
        request.setSize(10);
        assertNotEquals(first, codec.fingerprint(request, 101L));
    }
}
