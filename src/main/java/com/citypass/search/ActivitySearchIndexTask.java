package com.citypass.search;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ActivitySearchIndexTask {
    private Long activityId;
    private Long requestedVersion;
}
