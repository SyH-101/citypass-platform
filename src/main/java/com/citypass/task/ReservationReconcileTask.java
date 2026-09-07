package com.citypass.task;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.citypass.entity.LimitedPassStock;
import com.citypass.entity.ReservationOrder;
import com.citypass.mq.OrderMessagePublisher;
import com.citypass.service.ILimitedPassStockService;
import com.citypass.service.IReservationService;
import com.citypass.utils.RedisIdWorker;
import com.citypass.utils.RedisConstants;

import static com.citypass.utils.RedisConstants.RESERVATION_STOCK_KEY;

import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 限量预约对账任务：兜底 MQ 消息丢失与库存漂移
 * <p>
 * 核心思想：Redis/DB 库存都不是真相，订单表才是唯一账本，库存是派生值。
 * 每轮按 ①关单兜底 → ②补单 → ③库存重算 顺序执行，每轮收敛。
 * 订单表修复（①②）必须在库存重算（③）之前：账本先修对，重算才准确。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "reservation.reconcile.enabled", havingValue = "true", matchIfMissing = true)
public class ReservationReconcileTask {

    @Resource
    private ILimitedPassStockService limitedPassStockService;
    @Resource
    private IReservationService reservationService;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private RedissonClient redissonClient;
    @Resource
    private OrderMessagePublisher rocketMQProducer;
    @Resource
    private RedisIdWorker redisIdWorker;

    /** 限量预约结束多久后允许重算库存（等待消费队列排空） */
    private static final int RECONCILE_AFTER_END_MINUTES = 2;

    /**
     * 每分钟执行：关单兜底 → 补单 → 库存重算。
     * 多实例部署时用分布式锁保证同一时刻只有一个实例在对账；任务本身幂等，重复执行无副作用。
     */
    @Scheduled(fixedDelay = 60_000)
    public void reconcile() {
        RLock lock = redissonClient.getLock("lock:reservation:reconcile");
        if (!lock.tryLock()) {
            return; // 其他实例正在对账
        }
        try {
            closeTimeoutOrders();
            supplementMissingOrders();
            reconcileFinishedStocks();
        } catch (Exception e) {
            log.error("限量预约对账任务执行异常", e);
        } finally {
            lock.unlock();
        }
    }

    /**
     * ① 关单兜底：兜 TIMEOUT 延迟消息丢失。
     * 复用 cancelTimeoutOrder（CAS: WHERE status=未支付），与延迟消息并发执行也不重复回补库存。
     */
    private void closeTimeoutOrders() {
        List<ReservationOrder> expired = reservationService.lambdaQuery()
                .eq(ReservationOrder::getStatus, RedisConstants.ORDER_STATUS_UNPAID)
                .le(ReservationOrder::getOfferExpireTime, LocalDateTime.now())
                .list();
        for (ReservationOrder order : expired) {
            reservationService.cancelTimeoutOrder(order.getId());
        }
        if (!expired.isEmpty()) {
            // expired.size() 是扫描数，CAS 会跳过已支付/已取消的，实际关闭数 ≤ 扫描数
            log.warn("对账关单兜底：扫描到 {} 笔超时未支付订单（CAS 幂等，已处理过的自动跳过）", expired.size());
        }
    }

    /**
     * ② 补单：兜 CREATE 消息丢失（Redis 已扣库存、订单表缺失）。
     * 只对已结束的券执行——进行中的限量预约，claim 后消费者存在秒级落库延迟，
     * 对账过早跑差集会误判丢单（白打 MQ、日志误报）。
     * reservation:holders:{activityPassId}（Lua claim 用户）与订单表已落库用户的差集 = 丢单；
     * 重新发号补发 CREATE 消息，消费者幂等链（锁→count→唯一索引）保证不重复建单。
     */
    private void supplementMissingOrders() {
        List<LimitedPassStock> passes = limitedPassStockService.lambdaQuery()
                .lt(LimitedPassStock::getEndTime, LocalDateTime.now())
                .list();
        for (LimitedPassStock pass : passes) {
            Long activityPassId = pass.getActivityPassId();
            Set<String> claimed = stringRedisTemplate.opsForSet()
                    .members(RedisConstants.RESERVATION_HOLDER_KEY + activityPassId);
            if (claimed == null || claimed.isEmpty()) {
                continue;
            }
            List<ReservationOrder> created = reservationService.lambdaQuery()
                    .select(ReservationOrder::getUserId)
                    .eq(ReservationOrder::getActivityPassId, activityPassId)
                    .list();
            Set<String> createdSet = created.stream()
                    .map(o -> String.valueOf(o.getUserId()))
                    .collect(Collectors.toSet());

            for (String userId : claimed) {
                if (createdSet.contains(userId)) {
                    continue;
                }
                ReservationOrder order = new ReservationOrder();
                String claimedOrderId = stringRedisTemplate.opsForValue().get(
                        RedisConstants.RESERVATION_CLAIM_KEY + activityPassId + ":" + userId);
                order.setId(claimedOrderId == null
                        ? redisIdWorker.nextId("order") : Long.valueOf(claimedOrderId));
                order.setUserId(Long.valueOf(userId));
                order.setActivityPassId(activityPassId);
                try {
                    rocketMQProducer.sendOrderCreate(order);
                    log.warn("对账补单：activityPassId={}, userId={}, 新订单号={}", activityPassId, userId, order.getId());
                } catch (Exception e) {
                    // 发送失败不抛异常，下轮对账再次尝试；消费者幂等，重复补发安全
                    log.error("对账补单发送失败，activityPassId={}, userId={}", activityPassId, userId, e);
                }
            }
        }
    }

    /**
     * ③ 库存重算：只对已结束（且结束超过 2 分钟，队列基本排空）的券。
     * expected = initial_stock − 有效订单数(未支付+已支付)，Redis 与 DB 一起改写为 expected。
     * 预期值只来自订单表账本，不存在"修错方向"；每轮收敛，重复执行安全。
     * Redis 与 DB 均已一致则跳过写库。
     */
    private void reconcileFinishedStocks() {
        List<LimitedPassStock> finished = limitedPassStockService.lambdaQuery()
                .lt(LimitedPassStock::getEndTime, LocalDateTime.now().minusMinutes(RECONCILE_AFTER_END_MINUTES))
                .list();
        for (LimitedPassStock pass : finished) {
            Long activityPassId = pass.getActivityPassId();
            int initial = pass.getInitialStock() == null ? 0 : pass.getInitialStock();
            if (initial <= 0) {
                // 无账本基准（存量数据未回填/发布时未赋值），跳过重算避免误刷库存
                log.warn("对账库存重算跳过：activityPassId={} 缺少 initialStock，请确认是否执行回填 SQL", activityPassId);
                continue;
            }
            long valid = reservationService.lambdaQuery()
                    .eq(ReservationOrder::getActivityPassId, activityPassId)
                    .in(ReservationOrder::getStatus, 1, 2, 3, 5)
                    .count();
            int expected = Math.max(0, initial - (int) valid);

            boolean dbOk = pass.getStock() != null && pass.getStock() == expected;
            String redisStock = stringRedisTemplate.opsForValue().get(RESERVATION_STOCK_KEY + activityPassId);
            boolean redisOk = String.valueOf(expected).equals(redisStock);
            if (dbOk && redisOk) {
                continue; // 两边已一致，跳过写库
            }
            log.warn("对账库存重算：activityPassId={}, db={}, redis={} → expected={}（初始库存={}, 有效订单={}）",
                    activityPassId, pass.getStock(), redisStock, expected, initial, valid);
            // 告警与修复同一步：差异已记录，Redis 与 DB 统一改写为 expected
            stringRedisTemplate.opsForValue().set(
                    RedisConstants.RESERVATION_STOCK_KEY + activityPassId, String.valueOf(expected));
            limitedPassStockService.update(
                    Wrappers.<LimitedPassStock>lambdaUpdate()
                            .set(LimitedPassStock::getStock, expected)
                            .eq(LimitedPassStock::getActivityPassId, activityPassId));
        }
    }
}
