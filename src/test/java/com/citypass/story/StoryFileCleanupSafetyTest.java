package com.citypass.story;

import com.citypass.config.StoryFileProperties;
import org.junit.jupiter.api.Test;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class StoryFileCleanupSafetyTest {

    private final StoryFileCleanup cleanup =
            new StoryFileCleanup(null, null, new StoryFileProperties(), null);

    private Map<String, Object> row(Object... fields) {
        Map<String, Object> map = new HashMap<>();
        for (int i = 0; i < fields.length; i += 2) {
            map.put((String) fields[i], fields[i + 1]);
        }

        return map;
    }

    @Test
    void adoptedFinalIsProtectedEvenAfterItsOriginalCleanupDeadline() {
        assertFalse(
                cleanup.canDelete(
                        row(
                                "cleanup_after",
                                LocalDateTime.now().minusDays(1),
                                "kind",
                                "FINAL",
                                "object_key",
                                "final"),
                        row("state", "READY", "final_key", "final")));
    }

    @Test
    void activeAttemptIsProtectedButAnUnadoptedOlderAttemptCanBeSwept() {
        Map<String, Object> candidate =
                row(
                        "cleanup_after",
                        LocalDateTime.now().minusSeconds(1),
                        "kind",
                        "FINAL",
                        "object_key",
                        "candidate",
                        "attempt_id",
                        "a");
        Map<String, Object> attachment =
                row(
                        "state",
                        "PENDING",
                        "current_attempt",
                        "a",
                        "confirm_lease_until",
                        LocalDateTime.now().plusMinutes(1));
        assertFalse(cleanup.canDelete(candidate, attachment));
        attachment.put("current_attempt", "b");
        assertTrue(cleanup.canDelete(candidate, attachment));
    }

    @Test
    void graceIsHonoredAndDeletedTombstonesRemainSweepable() {
        Map<String, Object> object =
                row(
                        "cleanup_after",
                        LocalDateTime.now().plusSeconds(10),
                        "kind",
                        "STAGING",
                        "object_key",
                        "staging",
                        "state",
                        "DELETED");
        Map<String, Object> attachment = row("state", "DELETED");
        assertFalse(cleanup.canDelete(object, attachment));
        object.put("cleanup_after", LocalDateTime.now().minusSeconds(1));
        assertTrue(cleanup.canDelete(object, attachment));
    }
}
