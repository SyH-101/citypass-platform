package com.citypass.search;

import com.citypass.reliable.ReliableTaskRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.concurrent.Callable;

@Component
public class ActivitySearchMetrics {
    private final Timer latency;
    private final Counter failures;

    public ActivitySearchMetrics(MeterRegistry registry, ReliableTaskRepository taskRepository) {
        latency = Timer.builder("citypass.search.query.latency").publishPercentileHistogram().register(registry);
        failures = Counter.builder("citypass.search.query.failures").register(registry);
        Gauge.builder("citypass.search.index.tasks.ready", taskRepository,
                repository -> repository.countReadyByType(ReliableTaskRepository.INDEX_ACTIVITY_SEARCH)).register(registry);
        Gauge.builder("citypass.search.index.tasks.dead", taskRepository,
                repository -> repository.countByStatusAndType("DEAD", ReliableTaskRepository.INDEX_ACTIVITY_SEARCH)).register(registry);
    }

    public <T> T record(Callable<T> callable) throws Exception {
        Timer.Sample sample = Timer.start();
        try {
            return callable.call();
        } catch (Exception e) {
            failures.increment();
            throw e;
        } finally {
            sample.stop(latency);
        }
    }
}
