package com.citypass.search;

import cn.hutool.json.JSONUtil;
import org.springframework.stereotype.Component;

@Component
public class ActivitySearchIndexTaskHandler {
    private final ActivitySearchIndexService indexService;
    private final ActivitySearchSourceRepository sourceRepository;
    private final ActivitySearchDocumentMapper documentMapper;

    public ActivitySearchIndexTaskHandler(ActivitySearchIndexService indexService,
                                          ActivitySearchSourceRepository sourceRepository,
                                          ActivitySearchDocumentMapper documentMapper) {
        this.indexService = indexService;
        this.sourceRepository = sourceRepository;
        this.documentMapper = documentMapper;
    }

    public boolean isEnabled() {
        return indexService.isEnabled();
    }

    public void execute(String payload) throws Exception {
        ActivitySearchIndexTask task = JSONUtil.toBean(payload, ActivitySearchIndexTask.class);
        if (task == null || task.getActivityId() == null || task.getRequestedVersion() == null) {
            throw new IllegalArgumentException("活动搜索索引任务载荷无效");
        }
        ActivitySearchSource source = sourceRepository.findById(task.getActivityId());
        if (source == null) throw new IllegalStateException("活动不存在，无法创建软下架搜索文档: " + task.getActivityId());
        if (source.getSearchVersion() < task.getRequestedVersion()) {
            throw new IllegalStateException("数据库搜索版本落后于任务版本");
        }
        indexService.indexToAlias(documentMapper.from(source));
    }
}
