package com.citypass.search;

import com.citypass.config.ActivitySearchProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ActivitySearchRebuildServiceTest {
    @Test
    void failedBulkNeverSwitchesTheLiveAliasAndReleasesMaintenanceState() throws Exception {
        ActivitySearchIndexService index = mock(ActivitySearchIndexService.class);
        ActivitySearchSourceRepository source = mock(ActivitySearchSourceRepository.class);
        SearchRebuildStateRepository state = mock(SearchRebuildStateRepository.class);
        ActivitySearchProperties properties = new ActivitySearchProperties();
        properties.setRebuildBatchSize(10);
        when(index.isEnabled()).thenReturn(true);
        when(index.alias()).thenReturn("citypass-activity-search");
        when(index.aliasTargets()).thenReturn(Collections.singleton("citypass-activity-search-v-old"));
        when(source.countAll()).thenReturn(1L);
        ActivitySearchSource row = completeSource();
        when(source.findBatchAfter(0L, 10)).thenReturn(Collections.singletonList(row));
        doThrow(new IOException("simulated bulk failure")).when(index).bulkIndex(anyString(), any());

        ActivitySearchRebuildService service = new ActivitySearchRebuildService(index, source,
                new ActivitySearchDocumentMapper(), state, properties, new SimpleMeterRegistry());

        assertThrows(SearchRebuildException.class, service::rebuild);
        verify(index, never()).switchAlias(anyString(), any());
        verify(state).fail(anyString(), org.mockito.ArgumentMatchers.eq(1L), org.mockito.ArgumentMatchers.eq(0L));
    }

    private ActivitySearchSource completeSource() {
        ActivitySearchSource source = new ActivitySearchSource();
        source.setActivityId(1L);
        source.setVenueId(1L);
        source.setTitle("城市音乐节");
        source.setActivityCategory("MUSIC");
        source.setEventStartTime(LocalDateTime.of(2030, 1, 1, 10, 0));
        source.setEventEndTime(LocalDateTime.of(2030, 1, 1, 12, 0));
        source.setStatus(1);
        source.setSearchVersion(1L);
        return source;
    }
}
