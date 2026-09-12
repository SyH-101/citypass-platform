package com.citypass.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ActivitySearchPage {
    private List<ActivitySearchItem> items;
    private String nextCursor;
    private boolean hasMore;
}
