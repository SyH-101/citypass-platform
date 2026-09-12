package com.citypass.search;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class ActivitySearchRebuildService {
    private final ActivitySearchIndexService indexService;
    private final ActivitySearchSourceRepository sourceRepository;
    private final ActivitySearchDocumentMapper documentMapper;
    private final SearchRebuildStateRepository stateRepository;
    private final com.citypass.config.ActivitySearchProperties properties;
    private final Counter completed;
    private final Counter failed;

    public ActivitySearchRebuildService(ActivitySearchIndexService indexService,
                                        ActivitySearchSourceRepository sourceRepository,
                                        ActivitySearchDocumentMapper documentMapper,
                                        SearchRebuildStateRepository stateRepository,
                                        com.citypass.config.ActivitySearchProperties properties,
                                        MeterRegistry meterRegistry) {
        this.indexService = indexService;
        this.sourceRepository = sourceRepository;
        this.documentMapper = documentMapper;
        this.stateRepository = stateRepository;
        this.properties = properties;
        this.completed = Counter.builder("citypass.search.rebuild.completed").register(meterRegistry);
        this.failed = Counter.builder("citypass.search.rebuild.failed").register(meterRegistry);
    }

    public SearchRebuildState rebuild() {
        if (!indexService.isEnabled()) throw new SearchModuleUnavailableException("活动搜索模块未启用");
        long sourceCount = 0L;
        long indexedCount = 0L;
        boolean started = false;
        String target = indexService.alias() + "-v" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
                + "-" + UUID.randomUUID().toString().substring(0, 8);
        try {
            stateRepository.begin(target, null);
            started = true;
            Set<String> oldTargets = indexService.aliasTargets();
            stateRepository.previousIndex(oldTargets.isEmpty() ? null : String.join(",", oldTargets));
            indexService.createPhysicalIndex(target);
            sourceCount = sourceRepository.countAll();
            long afterId = 0L;
            int batchSize = Math.max(1, Math.min(1000, properties.getRebuildBatchSize()));
            while (true) {
                List<ActivitySearchSource> sources = sourceRepository.findBatchAfter(afterId, batchSize);
                if (sources.isEmpty()) break;
                indexService.bulkIndex(target, sources.stream().map(documentMapper::from).collect(Collectors.toList()));
                indexedCount += sources.size();
                afterId = sources.get(sources.size() - 1).getActivityId();
                stateRepository.progress(sourceCount, indexedCount);
            }
            indexService.refresh(target);
            long actualCount = indexService.count(target);
            if (indexedCount != sourceCount || actualCount != sourceCount) {
                throw new IllegalStateException("重建数量校验失败: mysql=" + sourceCount + ", submitted="
                        + indexedCount + ", elasticsearch=" + actualCount);
            }
            indexService.switchAlias(target, oldTargets);
            stateRepository.succeed(sourceCount, indexedCount);
            completed.increment();
            return stateRepository.get();
        } catch (Exception e) {
            if (started) stateRepository.fail(e.getMessage(), sourceCount, indexedCount);
            failed.increment();
            if (e instanceof SearchModuleUnavailableException) throw (SearchModuleUnavailableException) e;
            throw new SearchRebuildException("活动搜索索引重建失败: " + e.getMessage(), e);
        }
    }

    public SearchRebuildState state() {
        return stateRepository.get();
    }

    public void deleteInactiveIndex(String index) {
        try {
            indexService.deleteInactiveIndex(index);
        } catch (Exception e) {
            if (e instanceof RuntimeException) throw (RuntimeException) e;
            throw new IllegalStateException("清理旧索引失败", e);
        }
    }
}
