package com.citypass.task;

import cn.hutool.json.JSONUtil;
import com.citypass.entity.ReservationOrder;
import com.citypass.mq.OrderMessagePublisher;
import com.citypass.reliable.ReliableTask;
import com.citypass.reliable.ReliableTaskMetrics;
import com.citypass.reliable.ReliableTaskRepository;
import com.citypass.reliable.ReservationClaimTransfer;
import com.citypass.reliable.VenueCacheInvalidation;
import com.citypass.search.ActivitySearchIndexTaskHandler;
import com.citypass.utils.VenueCacheInvalidator;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static com.citypass.utils.RedisConstants.RESERVATION_STOCK_KEY;

/** 将与订单一起提交的本地可靠任务投递到 MQ/Redis。所有处理都支持安全重试。 */
@Slf4j
@Component
@ConditionalOnProperty(name = "reliable-task.enabled", havingValue = "true", matchIfMissing = true)
public class ReliableTaskScheduler {

    private static final DefaultRedisScript<Long> RESTORE_STOCK_SCRIPT;
    private static final DefaultRedisScript<Long> INIT_STOCK_SCRIPT;
    private static final DefaultRedisScript<Long> TRANSFER_RESERVATION_SCRIPT;
    static {
        RESTORE_STOCK_SCRIPT = new DefaultRedisScript<>();
        RESTORE_STOCK_SCRIPT.setLocation(new ClassPathResource("restore-stock.lua"));
        RESTORE_STOCK_SCRIPT.setResultType(Long.class);
        INIT_STOCK_SCRIPT = new DefaultRedisScript<>();
        INIT_STOCK_SCRIPT.setLocation(new ClassPathResource("init-reservation-stock.lua"));
        INIT_STOCK_SCRIPT.setResultType(Long.class);
        TRANSFER_RESERVATION_SCRIPT = new DefaultRedisScript<>();
        TRANSFER_RESERVATION_SCRIPT.setLocation(new ClassPathResource("transfer-reservation.lua"));
        TRANSFER_RESERVATION_SCRIPT.setResultType(Long.class);
    }

    private final ReliableTaskRepository repository;
    private final OrderMessagePublisher producer;
    private final StringRedisTemplate redisTemplate;
    private final VenueCacheInvalidator venueCacheInvalidator;
    private final ReliableTaskMetrics metrics;
    private final ActivitySearchIndexTaskHandler activitySearchIndexTaskHandler;
    private final String instanceId = UUID.randomUUID().toString();

    @Value("${reliable-task.batch-size:50}")
    private int batchSize;

    @Value("${reliable-task.lease-seconds:30}")
    private int leaseSeconds;

    public ReliableTaskScheduler(ReliableTaskRepository repository,
                                 OrderMessagePublisher producer,
                                 StringRedisTemplate redisTemplate,
                                 VenueCacheInvalidator venueCacheInvalidator,
                                 ReliableTaskMetrics metrics,
                                 ActivitySearchIndexTaskHandler activitySearchIndexTaskHandler) {
        this.repository = repository;
        this.producer = producer;
        this.redisTemplate = redisTemplate;
        this.venueCacheInvalidator = venueCacheInvalidator;
        this.metrics = metrics;
        this.activitySearchIndexTaskHandler = activitySearchIndexTaskHandler;
    }

    @Scheduled(fixedDelayString = "${reliable-task.fixed-delay-ms:1000}")
    public void relay() {
        try {
            // 每次只领取即将执行的一条，避免同批后部任务排队时租约先过期。
            for (int i = 0; i < batchSize; i++) {
                List<ReliableTask> tasks = repository.claimReady(
                        1, instanceId, leaseSeconds, activitySearchIndexTaskHandler.isEnabled());
                if (tasks.isEmpty()) break;
                execute(tasks.get(0));
            }
        } catch (Exception e) {
            log.error("可靠任务扫描失败", e);
        }
    }

    private void execute(ReliableTask task) {
        try {
            if (ReliableTaskRepository.CREATE_RESERVATION.equals(task.getTaskType())) {
                ReservationOrder request = JSONUtil.toBean(task.getPayload(), ReservationOrder.class);
                requireSendOk(producer.sendOrderCreate(request), "CREATE", request.getId());
            } else if (ReliableTaskRepository.ORDER_TIMEOUT.equals(task.getTaskType())) {
                Long orderId = Long.valueOf(task.getPayload());
                requireSendOk(producer.sendOrderTimeout(orderId), "TIMEOUT", orderId);
            } else if (ReliableTaskRepository.RESTORE_REDIS_STOCK.equals(task.getTaskType())) {
                ReservationOrder order = JSONUtil.toBean(task.getPayload(), ReservationOrder.class);
                Long result = redisTemplate.execute(
                        RESTORE_STOCK_SCRIPT,
                        Arrays.asList(RESERVATION_STOCK_KEY + order.getActivityPassId(), "reservation:restore:" + order.getId()),
                        String.valueOf(order.getActivityPassId()),
                        String.valueOf(order.getUserId()),
                        String.valueOf(order.getId()),
                        String.valueOf(order.getResourceVersion() == null ? 0L : order.getResourceVersion()));
                if (result == null || result < 0) {
                    throw new IllegalStateException("Redis 库存回补校验失败, code=" + result);
                }
            } else if (ReliableTaskRepository.TRANSFER_RESERVATION_CLAIM.equals(task.getTaskType())) {
                ReservationClaimTransfer transfer = JSONUtil.toBean(
                        task.getPayload(), ReservationClaimTransfer.class);
                Long result = redisTemplate.execute(
                        TRANSFER_RESERVATION_SCRIPT,
                        Arrays.asList("reservation:transfer:" + transfer.getOldOrderId()),
                        String.valueOf(transfer.getActivityPassId()),
                        String.valueOf(transfer.getOldUserId()),
                        String.valueOf(transfer.getOldOrderId()),
                        String.valueOf(transfer.getOldResourceVersion()),
                        String.valueOf(transfer.getNewUserId()),
                        String.valueOf(transfer.getNewOrderId()),
                        String.valueOf(transfer.getNewResourceVersion()));
                if (result == null || result < 0) {
                    throw new IllegalStateException("Redis 名额转移版本校验失败, code=" + result);
                }
            } else if (ReliableTaskRepository.INIT_RESERVATION_STOCK.equals(task.getTaskType())) {
                com.citypass.entity.LimitedPassStock pass = JSONUtil.toBean(
                        task.getPayload(), com.citypass.entity.LimitedPassStock.class);
                Long result = redisTemplate.execute(
                        INIT_STOCK_SCRIPT,
                        Arrays.asList(RESERVATION_STOCK_KEY + pass.getActivityPassId(),
                                "reservation:stock:init:" + pass.getActivityPassId()),
                        String.valueOf(pass.getInitialStock()));
                if (result == null) {
                    throw new IllegalStateException("Redis 库存初始化返回空");
                }
            } else if (ReliableTaskRepository.INVALIDATE_VENUE_CACHE.equals(task.getTaskType())) {
                VenueCacheInvalidation invalidation = JSONUtil.toBean(task.getPayload(), VenueCacheInvalidation.class);
                venueCacheInvalidator.evict(invalidation.getVenueId(), invalidation.getCacheVersion());
            } else if (ReliableTaskRepository.INDEX_ACTIVITY_SEARCH.equals(task.getTaskType())) {
                activitySearchIndexTaskHandler.execute(task.getPayload());
            } else {
                throw new IllegalArgumentException("未知可靠任务类型: " + task.getTaskType());
            }
            if (!repository.markDone(task)) {
                log.warn("任务执行完成但租约已失效: id={}, version={}", task.getId(), task.getVersion());
                return;
            }
            metrics.succeeded(task.getTaskType());
        } catch (Exception e) {
            ReliableTaskRepository.FailureDisposition disposition = repository.markFailed(task, e.getMessage());
            if (disposition == ReliableTaskRepository.FailureDisposition.DEAD) {
                metrics.dead(task.getTaskType());
                log.error("可靠任务进入死信: id={}, type={}", task.getId(), task.getTaskType(), e);
            } else if (disposition == ReliableTaskRepository.FailureDisposition.RETRY) {
                metrics.retried(task.getTaskType());
                log.warn("可靠任务执行失败，将指数退避重试: id={}, type={}", task.getId(), task.getTaskType(), e);
            } else {
                log.warn("可靠任务执行失败且已丢失租约: id={}, type={}", task.getId(), task.getTaskType(), e);
            }
        }
    }

    private void requireSendOk(SendResult result, String tag, Long id) {
        if (result == null || result.getSendStatus() != SendStatus.SEND_OK) {
            throw new IllegalStateException(tag + " 消息未被 Broker 确认: " + id);
        }
    }
}
