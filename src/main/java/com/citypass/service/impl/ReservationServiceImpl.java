package com.citypass.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.citypass.dto.Result;
import com.citypass.entity.ActivityPass;
import com.citypass.entity.LimitedPassStock;
import com.citypass.entity.ReservationOrder;
import com.citypass.entity.ReservationWaitlist;
import com.citypass.mapper.ReservationOrderMapper;
import com.citypass.mapper.ReservationWaitlistMapper;
import com.citypass.mq.OrderMessagePublisher;
import com.citypass.service.IReservationService;
import com.citypass.service.IActivityPassService;
import com.citypass.service.ILimitedPassStockService;
import com.citypass.utils.RedisIdWorker;
import com.citypass.utils.ReservationStatus;
import com.citypass.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.citypass.utils.RedisConstants.ORDER_STATUS_CANCELLED;
import static com.citypass.utils.RedisConstants.ORDER_STATUS_PAID;
import static com.citypass.utils.RedisConstants.ORDER_STATUS_UNPAID;
import static com.citypass.utils.RedisConstants.RESERVATION_CLAIM_KEY;
import static com.citypass.utils.RedisConstants.RESERVATION_REQUEST_KEY;
import static com.citypass.utils.RedisConstants.RESERVATION_REQUEST_OWNER_KEY;
import static com.citypass.utils.RedisConstants.RESERVATION_REQUEST_TTL_MINUTES;

/** 限量活动预约编排：MQ 削峰，Redis 快速占位，MySQL 保存最终订单与候补状态。 */
@Slf4j
@Service
public class ReservationServiceImpl extends ServiceImpl<ReservationOrderMapper, ReservationOrder>
        implements IReservationService {

    private static final DefaultRedisScript<Long> RESERVE_CLAIM_SCRIPT = script("reservation-claim.lua");
    private static final DefaultRedisScript<Long> RESERVE_ROLLBACK_SCRIPT = script("reservation-rollback.lua");
    private static final DefaultRedisScript<Long> CLEAR_STALE_CLAIM_SCRIPT = script("clear-stale-claim.lua");

    @Resource
    private RedisIdWorker redisIdWorker;
    @Resource
    private RedissonClient redissonClient;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private OrderMessagePublisher orderMessagePublisher;
    @Resource
    private ReservationTransactionalService transactionalService;
    @Resource
    private ReservationWaitlistMapper waitlistMapper;
    @Resource
    private ILimitedPassStockService limitedPassStockService;
    @Resource
    private IActivityPassService activityPassService;

    @Value("${reservation.offer-minutes:15}")
    private int offerMinutes;

    private static DefaultRedisScript<Long> script(String path) {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource(path));
        script.setResultType(Long.class);
        return script;
    }

    @Override
    public Result reserve(Long activityPassId, boolean acceptWaitlist) {
        if (activityPassId == null || UserHolder.getUser() == null) {
            return Result.fail("参数错误或用户未登录");
        }
        Long userId = UserHolder.getUser().getId();
        long requestId = redisIdWorker.nextId("reservation");
        ReservationOrder request = new ReservationOrder()
                .setId(requestId)
                .setUserId(userId)
                .setActivityPassId(activityPassId)
                .setSource(ReservationStatus.SOURCE_DIRECT)
                .setPromotionRound(0)
                .setAcceptWaitlist(acceptWaitlist);
        initializeRequest(requestId, userId);

        try {
            SendResult sent = orderMessagePublisher.sendOrderCreate(request);
            if (sent.getSendStatus() != SendStatus.SEND_OK) {
                writeRequestStatus(requestId, ReservationStatus.FAIL_SYSTEM);
                return Result.fail("预约服务繁忙，请稍后重试");
            }
        } catch (Exception e) {
            log.error("预约请求发送异常, requestId={}", requestId, e);
            writeRequestStatus(requestId, ReservationStatus.FAIL_SYSTEM);
            return Result.fail("预约服务繁忙，请稍后重试");
        }

        Map<String, Object> data = new HashMap<>(2);
        data.put("requestId", requestId);
        data.put("status", ReservationStatus.PROCESSING);
        return Result.ok(data);
    }

    /** MQ 重复投递安全：用户锁降低竞争，MySQL 有效订单/候补唯一索引最终兜底。 */
    @Override
    public void createOrderFromMQ(ReservationOrder request) {
        Long userId = request.getUserId();
        Long activityPassId = request.getActivityPassId();
        Long requestId = request.getId();
        if (userId == null || activityPassId == null || requestId == null) {
            throw new IllegalArgumentException("预约消息缺少 requestId/userId/passId");
        }

        RLock lock = redissonClient.getLock("lock:reservation:user:" + userId);
        if (!lock.tryLock()) {
            throw new IllegalStateException("获取用户预约锁失败，交给 MQ 重试: " + requestId);
        }
        try {
            String unavailableStatus = validateActivityWindow(activityPassId);
            if (unavailableStatus != null) {
                writeRequestStatus(requestId, unavailableStatus);
                return;
            }
            ReservationOrder activeOrder = findActiveOrder(activityPassId, userId);
            if (activeOrder != null) {
                writeRequestStatus(requestId, requestId.equals(activeOrder.getId())
                        ? orderStatus(activeOrder) : ReservationStatus.FAIL_REPEAT);
                return;
            }
            ReservationWaitlist activeWaitlist = findActiveWaitlist(activityPassId, userId);
            if (activeWaitlist != null) {
                writeRequestStatus(requestId, requestId.equals(activeWaitlist.getRequestId())
                        ? waitlistStatus(activeWaitlist) : ReservationStatus.FAIL_REPEAT);
                return;
            }

            Long claim = stringRedisTemplate.execute(
                    RESERVE_CLAIM_SCRIPT,
                    Collections.emptyList(),
                    activityPassId.toString(), userId.toString(), requestId.toString());
            if (claim == null) {
                throw new IllegalStateException("Redis 预约占位返回空");
            }
            if (claim == 1L) {
                handleSoldOut(request);
                return;
            }
            if (claim == 2L) {
                writeRequestStatus(requestId, ReservationStatus.FAIL_REPEAT);
                return;
            }

            ReservationTransactionalService.CreateResult created;
            try {
                created = transactionalService.createOrder(request, offerMinutes);
            } catch (DuplicateKeyException duplicate) {
                rollbackClaim(activityPassId, userId, requestId);
                ReservationOrder winner = findActiveOrder(activityPassId, userId);
                writeRequestStatus(requestId,
                        winner != null && requestId.equals(winner.getId())
                                ? orderStatus(winner) : ReservationStatus.FAIL_REPEAT);
                return;
            }

            if (created == ReservationTransactionalService.CreateResult.SOLD_OUT) {
                clearStaleClaim(activityPassId, userId, requestId);
                handleSoldOut(request);
                return;
            }
            if (created != ReservationTransactionalService.CreateResult.CREATED) {
                if (created == ReservationTransactionalService.CreateResult.NOT_STARTED) {
                    rollbackClaim(activityPassId, userId, requestId);
                    writeRequestStatus(requestId, ReservationStatus.FAIL_NOT_STARTED);
                } else {
                    clearStaleClaim(activityPassId, userId, requestId);
                    writeRequestStatus(requestId,
                            created == ReservationTransactionalService.CreateResult.ENDED
                                    ? ReservationStatus.FAIL_ENDED : ReservationStatus.FAIL_UNAVAILABLE);
                }
                return;
            }
            writeRequestStatus(requestId, ReservationStatus.RESERVED);
        } catch (RuntimeException e) {
            log.error("预约请求异步处理失败，等待 MQ 重试, requestId={}", requestId, e);
            throw e;
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    private void handleSoldOut(ReservationOrder request) {
        if (!Boolean.TRUE.equals(request.getAcceptWaitlist())) {
            writeRequestStatus(request.getId(), ReservationStatus.FAIL_STOCK);
            return;
        }
        ReservationWaitlist waitlist = new ReservationWaitlist()
                .setRequestId(request.getId())
                .setActivityPassId(request.getActivityPassId())
                .setUserId(request.getUserId());
        try {
            transactionalService.createWaitlist(waitlist);
            writeRequestStatus(request.getId(), ReservationStatus.WAITLISTED);
        } catch (DuplicateKeyException duplicate) {
            ReservationWaitlist existing = findActiveWaitlist(request.getActivityPassId(), request.getUserId());
            writeRequestStatus(request.getId(),
                    existing != null && request.getId().equals(existing.getRequestId())
                            ? waitlistStatus(existing) : ReservationStatus.FAIL_REPEAT);
        }
    }

    private String validateActivityWindow(Long activityPassId) {
        ActivityPass pass = activityPassService.getById(activityPassId);
        LimitedPassStock stock = limitedPassStockService.getById(activityPassId);
        if (pass == null || stock == null || !Integer.valueOf(1).equals(pass.getStatus())
                || !Integer.valueOf(1).equals(pass.getType())) {
            return ReservationStatus.FAIL_UNAVAILABLE;
        }
        LocalDateTime now = LocalDateTime.now();
        if (stock.getBeginTime() != null && stock.getBeginTime().isAfter(now)) {
            return ReservationStatus.FAIL_NOT_STARTED;
        }
        if (stock.getEndTime() == null || !stock.getEndTime().isAfter(now)) {
            return ReservationStatus.FAIL_ENDED;
        }
        return null;
    }

    @Override
    public void cancelTimeoutOrder(Long orderId) {
        ReservationTransactionalService.ReleaseResult result = transactionalService.releaseOrPromote(
                orderId, null, ReservationStatus.EXPIRED, offerMinutes);
        publishReleaseResult(result);
    }

    @Override
    public Result cancelOrder(Long orderId) {
        if (orderId == null || UserHolder.getUser() == null) {
            return Result.fail("订单号不能为空或用户未登录");
        }
        ReservationTransactionalService.ReleaseResult result = transactionalService.releaseOrPromote(
                orderId, UserHolder.getUser().getId(), ReservationStatus.CANCELLED,
                offerMinutes);
        if (result.getAction() == ReservationTransactionalService.ReleaseAction.DENIED) {
            return Result.fail("无权取消该预约");
        }
        if (result.getAction() == ReservationTransactionalService.ReleaseAction.SKIPPED) {
            return Result.fail("订单不存在或当前状态不允许取消");
        }
        publishReleaseResult(result);
        return Result.ok(result.getAction().name());
    }

    @Override
    public Result cancelWaitlist(Long requestId) {
        if (requestId == null || UserHolder.getUser() == null) {
            return Result.fail("请求号不能为空或用户未登录");
        }
        Long userId = UserHolder.getUser().getId();
        ReservationWaitlist waitlist = findWaitlistByRequest(requestId);
        if (waitlist == null || !userId.equals(waitlist.getUserId())) {
            return Result.fail("候补记录不存在或无权操作");
        }
        if (ReservationStatus.WAITING.equals(waitlist.getStatus())) {
            boolean cancelled = transactionalService.cancelWaiting(requestId, userId);
            if (cancelled) {
                writeRequestStatus(requestId, ReservationStatus.CANCELLED);
                return Result.ok("已退出候补");
            }
            return Result.fail("候补状态已变化，请刷新后重试");
        }
        if (ReservationStatus.OFFERED.equals(waitlist.getStatus())) {
            return cancelOrder(waitlist.getOfferedOrderId());
        }
        return Result.fail("当前候补状态不允许退出");
    }

    @Override
    public Result payOrder(Long orderId) {
        if (orderId == null || UserHolder.getUser() == null) {
            return Result.fail("订单号不能为空或用户未登录");
        }
        boolean paid = transactionalService.payUnpaidOrder(orderId, UserHolder.getUser().getId());
        if (paid) {
            writeRequestStatus(orderId, ReservationStatus.PAID);
        }
        return paid ? Result.ok("支付成功") : Result.fail("订单不存在、资格已过期或状态不允许支付");
    }

    @Override
    public Result getReservationResult(Long requestId) {
        if (requestId == null || UserHolder.getUser() == null) {
            return Result.fail("请求号不能为空或用户未登录");
        }
        Long userId = UserHolder.getUser().getId();

        ReservationOrder order = getById(requestId);
        if (order != null) {
            if (!userId.equals(order.getUserId())) {
                return Result.fail("无权查询该预约");
            }
            return Result.ok(orderResult(order));
        }

        ReservationWaitlist waitlist = findWaitlistByRequest(requestId);
        if (waitlist != null) {
            if (!userId.equals(waitlist.getUserId())) {
                return Result.fail("无权查询该候补记录");
            }
            return Result.ok(waitlistResult(waitlist));
        }

        String owner = stringRedisTemplate.opsForValue().get(RESERVATION_REQUEST_OWNER_KEY + requestId);
        if (owner != null && !userId.toString().equals(owner)) {
            return Result.fail("无权查询该预约");
        }
        String status = stringRedisTemplate.opsForValue().get(RESERVATION_REQUEST_KEY + requestId);
        Map<String, Object> data = new HashMap<>(3);
        data.put("requestId", requestId);
        data.put("status", status == null ? "NOT_FOUND" : status);
        return Result.ok(data);
    }

    private Map<String, Object> orderResult(ReservationOrder order) {
        Map<String, Object> data = new HashMap<>(8);
        data.put("requestId", order.getId());
        data.put("orderId", order.getId());
        data.put("status", orderStatus(order));
        data.put("orderStatus", order.getStatus());
        data.put("source", order.getSource());
        data.put("promotionRound", order.getPromotionRound());
        data.put("offerExpireTime", order.getOfferExpireTime());
        return data;
    }

    private Map<String, Object> waitlistResult(ReservationWaitlist waitlist) {
        Map<String, Object> data = new HashMap<>(8);
        data.put("requestId", waitlist.getRequestId());
        data.put("status", waitlistStatus(waitlist));
        data.put("waitlistStatus", waitlist.getStatus());
        data.put("orderId", waitlist.getOfferedOrderId());
        data.put("offerExpireTime", waitlist.getOfferExpireTime());
        if (ReservationStatus.WAITING.equals(waitlist.getStatus())) {
            data.put("position", waitlistMapper.countWaitingAhead(
                    waitlist.getActivityPassId(), waitlist.getRequestId()) + 1);
        }
        return data;
    }

    private String orderStatus(ReservationOrder order) {
        if (Integer.valueOf(ORDER_STATUS_UNPAID).equals(order.getStatus())) {
            return ReservationStatus.RESERVED;
        }
        if (Integer.valueOf(ORDER_STATUS_PAID).equals(order.getStatus())) {
            return ReservationStatus.PAID;
        }
        if (Integer.valueOf(ORDER_STATUS_CANCELLED).equals(order.getStatus())) {
            return ReservationStatus.CANCELLED;
        }
        return "ORDER_STATUS_" + order.getStatus();
    }

    private String waitlistStatus(ReservationWaitlist waitlist) {
        if (ReservationStatus.WAITING.equals(waitlist.getStatus())) {
            return ReservationStatus.WAITLISTED;
        }
        if (ReservationStatus.OFFERED.equals(waitlist.getStatus())) {
            return ReservationStatus.RESERVED;
        }
        if (ReservationStatus.ACCEPTED.equals(waitlist.getStatus())) {
            return ReservationStatus.PAID;
        }
        return ReservationStatus.CANCELLED;
    }

    private ReservationOrder findActiveOrder(Long activityPassId, Long userId) {
        return lambdaQuery()
                .eq(ReservationOrder::getActivityPassId, activityPassId)
                .eq(ReservationOrder::getUserId, userId)
                .in(ReservationOrder::getStatus, Arrays.asList(1, 2, 3, 5))
                .last("LIMIT 1")
                .one();
    }

    private ReservationWaitlist findActiveWaitlist(Long activityPassId, Long userId) {
        return waitlistMapper.selectOne(new QueryWrapper<ReservationWaitlist>()
                .eq("activity_pass_id", activityPassId)
                .eq("user_id", userId)
                .in("status", ReservationStatus.WAITING, ReservationStatus.OFFERED)
                .last("LIMIT 1"));
    }

    private ReservationWaitlist findWaitlistByRequest(Long requestId) {
        return waitlistMapper.selectOne(new QueryWrapper<ReservationWaitlist>()
                .eq("request_id", requestId)
                .last("LIMIT 1"));
    }

    private void publishReleaseResult(ReservationTransactionalService.ReleaseResult result) {
        if (result.getAction() == ReservationTransactionalService.ReleaseAction.SKIPPED
                || result.getAction() == ReservationTransactionalService.ReleaseAction.DENIED) {
            return;
        }
        ReservationOrder released = result.getReleasedOrder();
        writeRequestStatus(released.getId(), ReservationStatus.CANCELLED);
        if (result.getAction() == ReservationTransactionalService.ReleaseAction.PROMOTED) {
            ReservationOrder promoted = result.getPromotedOrder();
            writeRequestStatus(promoted.getId(), ReservationStatus.RESERVED);
            log.info("名额自动补位完成: oldOrderId={}, newOrderId={}, round={}",
                    released.getId(), promoted.getId(), promoted.getPromotionRound());
        } else {
            log.info("预约名额已释放并回补库存: orderId={}", released.getId());
        }
    }

    private void initializeRequest(Long requestId, Long userId) {
        stringRedisTemplate.opsForValue().set(
                RESERVATION_REQUEST_OWNER_KEY + requestId, userId.toString(),
                RESERVATION_REQUEST_TTL_MINUTES, TimeUnit.MINUTES);
        writeRequestStatus(requestId, ReservationStatus.PROCESSING);
    }

    private void writeRequestStatus(Long requestId, String status) {
        stringRedisTemplate.opsForValue().set(
                RESERVATION_REQUEST_KEY + requestId, status,
                RESERVATION_REQUEST_TTL_MINUTES, TimeUnit.MINUTES);
    }

    private void rollbackClaim(Long activityPassId, Long userId, Long requestId) {
        Long result = stringRedisTemplate.execute(
                RESERVE_ROLLBACK_SCRIPT,
                Collections.emptyList(),
                activityPassId.toString(), userId.toString(), requestId.toString());
        if (result != null && result < 0) {
            log.warn("Redis 库存 key 不存在，占位回滚交给对账修复, requestId={}", requestId);
        }
    }

    private void clearStaleClaim(Long activityPassId, Long userId, Long requestId) {
        stringRedisTemplate.execute(
                CLEAR_STALE_CLAIM_SCRIPT,
                Collections.emptyList(),
                activityPassId.toString(), userId.toString(), requestId.toString());
    }
}
