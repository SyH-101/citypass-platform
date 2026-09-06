package com.hmdp.task;

import cn.hutool.json.JSONUtil;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mq.OrderMessagePublisher;
import com.hmdp.reliable.ReliableTask;
import com.hmdp.reliable.ReliableTaskRepository;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

import static com.hmdp.utils.RedisConstants.SECKILL_STOCK_KEY;

/** 将与订单一起提交的本地可靠任务投递到 MQ/Redis。所有处理都支持安全重试。 */
@Slf4j
@Component
@ConditionalOnProperty(name = "reliable-task.enabled", havingValue = "true", matchIfMissing = true)
public class ReliableTaskScheduler {

    private static final DefaultRedisScript<Long> RESTORE_STOCK_SCRIPT;
    private static final DefaultRedisScript<Long> INIT_STOCK_SCRIPT;
    private static final long RESTORE_MARKER_TTL_SECONDS = 7 * 24 * 3600L;

    static {
        RESTORE_STOCK_SCRIPT = new DefaultRedisScript<>();
        RESTORE_STOCK_SCRIPT.setLocation(new ClassPathResource("restore-stock.lua"));
        RESTORE_STOCK_SCRIPT.setResultType(Long.class);
        INIT_STOCK_SCRIPT = new DefaultRedisScript<>();
        INIT_STOCK_SCRIPT.setLocation(new ClassPathResource("init-seckill-stock.lua"));
        INIT_STOCK_SCRIPT.setResultType(Long.class);
    }

    private final ReliableTaskRepository repository;
    private final OrderMessagePublisher producer;
    private final StringRedisTemplate redisTemplate;
    private final RedissonClient redissonClient;

    public ReliableTaskScheduler(ReliableTaskRepository repository,
                                 OrderMessagePublisher producer,
                                 StringRedisTemplate redisTemplate,
                                 RedissonClient redissonClient) {
        this.repository = repository;
        this.producer = producer;
        this.redisTemplate = redisTemplate;
        this.redissonClient = redissonClient;
    }

    @Scheduled(fixedDelayString = "${reliable-task.fixed-delay-ms:1000}")
    public void relay() {
        RLock lock = redissonClient.getLock("lock:reliable-task:relay");
        if (!lock.tryLock()) {
            return;
        }
        try {
            List<ReliableTask> tasks = repository.findReady(100);
            for (ReliableTask task : tasks) {
                execute(task);
            }
        } catch (Exception e) {
            log.error("可靠任务扫描失败", e);
        } finally {
            lock.unlock();
        }
    }

    private void execute(ReliableTask task) {
        try {
            if (ReliableTaskRepository.ORDER_TIMEOUT.equals(task.getTaskType())) {
                producer.sendOrderTimeout(Long.valueOf(task.getPayload()));
            } else if (ReliableTaskRepository.RESTORE_REDIS_STOCK.equals(task.getTaskType())) {
                VoucherOrder order = JSONUtil.toBean(task.getPayload(), VoucherOrder.class);
                Long result = redisTemplate.execute(
                        RESTORE_STOCK_SCRIPT,
                        Arrays.asList(SECKILL_STOCK_KEY + order.getVoucherId(), "seckill:restore:" + order.getId()),
                        String.valueOf(RESTORE_MARKER_TTL_SECONDS));
                if (result == null || result < 0) {
                    throw new IllegalStateException("Redis 秒杀库存尚未初始化");
                }
            } else if (ReliableTaskRepository.INIT_SECKILL_STOCK.equals(task.getTaskType())) {
                com.hmdp.entity.SeckillVoucher voucher = JSONUtil.toBean(
                        task.getPayload(), com.hmdp.entity.SeckillVoucher.class);
                Long result = redisTemplate.execute(
                        INIT_STOCK_SCRIPT,
                        Arrays.asList(SECKILL_STOCK_KEY + voucher.getVoucherId(),
                                "seckill:stock:init:" + voucher.getVoucherId()),
                        String.valueOf(voucher.getInitialStock()));
                if (result == null) {
                    throw new IllegalStateException("Redis 库存初始化返回空");
                }
            } else {
                throw new IllegalArgumentException("未知可靠任务类型: " + task.getTaskType());
            }
            repository.markDone(task.getId());
        } catch (Exception e) {
            repository.markFailed(task.getId(), task.getRetryCount(), e.getMessage());
            log.warn("可靠任务执行失败，稍后重试: id={}, type={}", task.getId(), task.getTaskType(), e);
        }
    }
}
