package com.citypass.task;

import com.citypass.mq.OrderMessagePublisher;
import com.citypass.reliable.ReliableTask;
import com.citypass.reliable.ReliableTaskMetrics;
import com.citypass.reliable.ReliableTaskRepository;
import com.citypass.search.ActivitySearchIndexTaskHandler;
import com.citypass.utils.VenueCacheInvalidator;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReliableTaskSchedulerGatewayTest {

    @Test
    void gatewayPurgeFailureCannotMarkReliableTaskDone() {
        ReliableTaskRepository repository = mock(ReliableTaskRepository.class);
        VenueCacheInvalidator invalidator = mock(VenueCacheInvalidator.class);
        ReliableTaskMetrics metrics = mock(ReliableTaskMetrics.class);
        ReliableTaskScheduler scheduler = new ReliableTaskScheduler(
                repository,
                mock(OrderMessagePublisher.class),
                mock(StringRedisTemplate.class),
                invalidator,
                metrics,
                mock(ActivitySearchIndexTaskHandler.class));

        ReliableTask task = new ReliableTask();
        task.setId(99L);
        task.setTaskType(ReliableTaskRepository.INVALIDATE_VENUE_CACHE);
        task.setPayload("{\"venueId\":7,\"cacheVersion\":6}");
        task.setRetryCount(0);
        task.setMaxRetry(12);
        task.setLockedBy("worker-a");
        task.setVersion(3L);

        doThrow(new IllegalStateException("gateway-b purge failed"))
                .when(invalidator).evict(7L, 6L);
        when(repository.markFailed(eq(task), contains("gateway-b")))
                .thenReturn(ReliableTaskRepository.FailureDisposition.RETRY);

        ReflectionTestUtils.invokeMethod(scheduler, "execute", task);

        verify(repository, never()).markDone(task);
        verify(repository).markFailed(eq(task), contains("gateway-b"));
        verify(metrics).retried(ReliableTaskRepository.INVALIDATE_VENUE_CACHE);
    }
}
