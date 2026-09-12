package com.citypass.search;

import lombok.Data;

import java.util.List;

@Data
public class ActivitySearchCursor {
    private String pitId;
    private List<Object> sortValues;
    private String queryFingerprint;
    private Long snapshotEpochMillis;
    private Long expiresAtEpochMillis;
}
