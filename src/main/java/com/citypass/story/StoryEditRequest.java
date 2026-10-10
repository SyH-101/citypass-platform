package com.citypass.story;

import lombok.Data;
import java.util.List;

@Data
public class StoryEditRequest {

    private String title;
    private String content;
    private Long activityPassId;
    private Long version;
    private List<Long> attachmentIds;

    /**
     * Retained legacy images are server validated; new writes cannot introduce legacy paths.
     */
    private List<String> legacyImages;
}
