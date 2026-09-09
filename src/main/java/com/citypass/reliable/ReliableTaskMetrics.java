package com.citypass.reliable;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** Micrometer signals for task backlog, retries, dead letters and waitlist promotion latency. */
@Component
public class ReliableTaskMetrics {
    private final MeterRegistry registry;

    public ReliableTaskMetrics(MeterRegistry registry, ReliableTaskRepository repository) {
        this.registry = registry;
        Gauge.builder("citypass.reliable.tasks.ready", repository,
                ReliableTaskRepository::countReady).register(registry);
        Gauge.builder("citypass.reliable.tasks.dead", repository,
                r -> r.countByStatus("DEAD")).register(registry);
        Gauge.builder("citypass.reliable.tasks.oldest.ready.seconds", repository,
                ReliableTaskRepository::oldestReadyAgeSeconds).register(registry);
    }

    public void succeeded(String type) {
        Counter.builder("citypass.reliable.tasks.completed").tag("type", type).register(registry).increment();
    }

    public void retried(String type) {
        Counter.builder("citypass.reliable.tasks.retried").tag("type", type).register(registry).increment();
    }

    public void dead(String type) {
        Counter.builder("citypass.reliable.tasks.dead.total").tag("type", type).register(registry).increment();
    }

    public void recordPromotionLatency(Duration duration) {
        Timer.builder("citypass.reservation.waitlist.promotion")
                .publishPercentileHistogram()
                .register(registry)
                .record(duration);
    }
}
