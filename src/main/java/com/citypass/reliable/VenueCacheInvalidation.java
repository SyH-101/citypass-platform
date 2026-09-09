package com.citypass.reliable;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class VenueCacheInvalidation {
    private Long venueId;
    private Long cacheVersion;
}
