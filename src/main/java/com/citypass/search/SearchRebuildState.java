package com.citypass.search;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class SearchRebuildState {
    private Integer id;
    private String status;
    private Boolean writeBlocked;
    private String targetIndex;
    private String previousIndex;
    private Long sourceCount;
    private Long indexedCount;
    private String lastError;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
}
