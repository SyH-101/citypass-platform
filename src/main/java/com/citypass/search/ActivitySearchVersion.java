package com.citypass.search;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ActivitySearchVersion {
    private Long activityId;
    private Long searchVersion;
}
