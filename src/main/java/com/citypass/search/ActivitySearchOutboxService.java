package com.citypass.search;

import cn.hutool.json.JSONUtil;
import com.citypass.reliable.ReliableTaskRepository;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ActivitySearchOutboxService {
    private final ActivitySearchSourceRepository sourceRepository;
    private final ReliableTaskRepository taskRepository;

    public ActivitySearchOutboxService(ActivitySearchSourceRepository sourceRepository,
                                       ReliableTaskRepository taskRepository) {
        this.sourceRepository = sourceRepository;
        this.taskRepository = taskRepository;
    }

    public void enqueue(Long activityId, Long version) {
        if (activityId == null || version == null) throw new IllegalArgumentException("活动搜索任务缺少版本");
        ActivitySearchIndexTask payload = new ActivitySearchIndexTask(activityId, version);
        taskRepository.enqueue(ReliableTaskRepository.INDEX_ACTIVITY_SEARCH,
                "index-activity-search:" + activityId + ":" + version, JSONUtil.toJsonStr(payload));
    }

    public void bumpVenueDocumentsAndEnqueue(Long venueId) {
        List<ActivitySearchVersion> versions = sourceRepository.bumpVenueDocuments(venueId);
        for (ActivitySearchVersion version : versions) {
            enqueue(version.getActivityId(), version.getSearchVersion());
        }
    }
}
