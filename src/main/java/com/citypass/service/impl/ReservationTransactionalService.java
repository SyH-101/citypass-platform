package com.citypass.service.impl;

import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.citypass.entity.LimitedPassStock;
import com.citypass.entity.ReservationOrder;
import com.citypass.entity.ReservationRequest;
import com.citypass.entity.ReservationWaitlist;
import com.citypass.mapper.LimitedPassStockMapper;
import com.citypass.mapper.ReservationOrderMapper;
import com.citypass.mapper.ReservationRequestMapper;
import com.citypass.mapper.ReservationWaitlistMapper;
import com.citypass.reliable.ReliableTaskMetrics;
import com.citypass.reliable.ReliableTaskRepository;
import com.citypass.reliable.ReservationClaimTransfer;
import com.citypass.utils.ReservationStatus;
import lombok.AllArgsConstructor;
import lombok.Data;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;

import static com.citypass.utils.RedisConstants.ORDER_STATUS_CANCELLED;
import static com.citypass.utils.RedisConstants.ORDER_STATUS_PAID;
import static com.citypass.utils.RedisConstants.ORDER_STATUS_UNPAID;

/** 预约请求、订单、候补状态和 Outbox 的数据库事务边界。 */
@Service
public class ReservationTransactionalService {

    public enum CreateResult { CREATED, SOLD_OUT, NOT_STARTED, ENDED, UNAVAILABLE }
    public enum WaitlistResult { WAITLISTED, RETRY_DIRECT, ENDED }
    public enum ReleaseAction { SKIPPED, DENIED, RELEASED, PROMOTED }

    @Data
    @AllArgsConstructor
    public static class ReleaseResult {
        private ReleaseAction action;
        private ReservationOrder releasedOrder;
        private ReservationOrder promotedOrder;
    }

    private final ReservationOrderMapper orderMapper;
    private final ReservationWaitlistMapper waitlistMapper;
    private final LimitedPassStockMapper stockMapper;
    private final ReservationRequestMapper requestMapper;
    private final ReliableTaskRepository reliableTaskRepository;
    private final ReliableTaskMetrics metrics;

    public ReservationTransactionalService(ReservationOrderMapper orderMapper,
                                            ReservationWaitlistMapper waitlistMapper,
                                            LimitedPassStockMapper stockMapper,
                                            ReservationRequestMapper requestMapper,
                                            ReliableTaskRepository reliableTaskRepository,
                                            ReliableTaskMetrics metrics) {
        this.orderMapper = orderMapper;
        this.waitlistMapper = waitlistMapper;
        this.stockMapper = stockMapper;
        this.requestMapper = requestMapper;
        this.reliableTaskRepository = reliableTaskRepository;
        this.metrics = metrics;
    }

    /** HTTP 入口只提交本地事务：请求事实和 CREATE 投递意图要么同时存在，要么同时不存在。 */
    @Transactional(rollbackFor = Exception.class)
    public void acceptRequest(ReservationOrder request) {
        ReservationRequest fact = new ReservationRequest()
                .setRequestId(request.getId())
                .setUserId(request.getUserId())
                .setActivityPassId(request.getActivityPassId())
                .setAcceptWaitlist(Boolean.TRUE.equals(request.getAcceptWaitlist()))
                .setStatus(ReservationStatus.PROCESSING);
        requestMapper.insert(fact);
        reliableTaskRepository.enqueue(
                ReliableTaskRepository.CREATE_RESERVATION,
                "create-reservation:" + request.getId(),
                JSONUtil.toJsonStr(request));
    }

    /** DB 条件扣库存、订单、请求终态和按真实截止时间执行的关单任务原子提交。 */
    @Transactional(rollbackFor = Exception.class)
    public CreateResult createOrder(ReservationOrder order, int offerMinutes) throws DuplicateKeyException {
        LocalDateTime now = LocalDateTime.now();
        int stockRows = stockMapper.update(null,
                new UpdateWrapper<LimitedPassStock>()
                        .setSql("stock = stock - 1")
                        .eq("activity_pass_id", order.getActivityPassId())
                        .le("begin_time", now)
                        .gt("end_time", now)
                        .gt("stock", 0));
        if (stockRows == 0) {
            LimitedPassStock current = stockMapper.selectById(order.getActivityPassId());
            if (current == null) return CreateResult.UNAVAILABLE;
            if (current.getBeginTime() != null && current.getBeginTime().isAfter(now)) return CreateResult.NOT_STARTED;
            if (current.getEndTime() == null || !current.getEndTime().isAfter(now)) return CreateResult.ENDED;
            return CreateResult.SOLD_OUT;
        }

        LimitedPassStock current = stockMapper.selectById(order.getActivityPassId());
        LocalDateTime expiresAt = capOfferExpiry(now, offerMinutes, current == null ? null : current.getEndTime());
        order.setStatus(ORDER_STATUS_UNPAID);
        order.setSource(order.getSource() == null ? ReservationStatus.SOURCE_DIRECT : order.getSource());
        order.setPromotionRound(order.getPromotionRound() == null ? 0 : order.getPromotionRound());
        order.setResourceVersion(order.getResourceVersion() == null ? 0L : order.getResourceVersion());
        order.setOfferExpireTime(expiresAt);
        orderMapper.insert(order);
        updateRequestState(order.getId(), ReservationStatus.RESERVED, order.getId(), null);
        enqueueTimeout(order.getId(), expiresAt);
        return CreateResult.CREATED;
    }

    /**
     * 候补入队与释放名额使用同一库存行作为活动级串行点。
     * 若释放先完成导致 DB 已有库存，则让 MQ 重试直接预约，避免“有库存却排队”。
     */
    @Transactional(rollbackFor = Exception.class)
    public WaitlistResult createWaitlist(ReservationWaitlist waitlist) throws DuplicateKeyException {
        LimitedPassStock stock = stockMapper.selectByIdForUpdate(waitlist.getActivityPassId());
        if (stock == null || stock.getEndTime() == null || !stock.getEndTime().isAfter(LocalDateTime.now())) {
            updateRequestState(waitlist.getRequestId(), ReservationStatus.FAIL_ENDED, null, null);
            return WaitlistResult.ENDED;
        }
        if (stock.getStock() != null && stock.getStock() > 0) {
            return WaitlistResult.RETRY_DIRECT;
        }
        waitlist.setStatus(ReservationStatus.WAITING).setWaitExpireTime(stock.getEndTime());
        waitlistMapper.insert(waitlist);
        updateRequestState(waitlist.getRequestId(), ReservationStatus.WAITLISTED, null, null);
        return WaitlistResult.WAITLISTED;
    }

    @Transactional(rollbackFor = Exception.class)
    public ReleaseResult cancelAndPromote(Long orderId, Long expectedUserId, int offerMinutes) {
        return releaseOrPromote(orderId, expectedUserId, ReservationStatus.CANCELLED, offerMinutes, false);
    }

    @Transactional(rollbackFor = Exception.class)
    public ReleaseResult expireAndPromote(Long orderId, int offerMinutes) {
        return releaseOrPromote(orderId, null, ReservationStatus.EXPIRED, offerMinutes, true);
    }

    /** Kept as a source-compatible facade for focused tests and older callers. */
    @Transactional(rollbackFor = Exception.class)
    public ReleaseResult releaseOrPromote(Long orderId, Long expectedUserId,
                                          String terminalStatus, int offerMinutes) {
        return releaseOrPromote(orderId, expectedUserId, terminalStatus, offerMinutes,
                ReservationStatus.EXPIRED.equals(terminalStatus));
    }

    /**
     * The stock row serializes all releases and waitlist inserts for one activity. A timeout additionally
     * requires offer_expire_time <= now; therefore an early message can never cancel a still-valid order.
     */
    @Transactional(rollbackFor = Exception.class)
    protected ReleaseResult releaseOrPromote(Long orderId, Long expectedUserId,
                                             String waitlistTerminalStatus, int offerMinutes,
                                             boolean requireExpired) {
        ReservationOrder oldOrder = orderMapper.selectById(orderId);
        if (oldOrder == null || !Integer.valueOf(ORDER_STATUS_UNPAID).equals(oldOrder.getStatus())) {
            return new ReleaseResult(ReleaseAction.SKIPPED, oldOrder, null);
        }
        if (expectedUserId != null && !expectedUserId.equals(oldOrder.getUserId())) {
            return new ReleaseResult(ReleaseAction.DENIED, oldOrder, null);
        }

        LimitedPassStock pass = stockMapper.selectByIdForUpdate(oldOrder.getActivityPassId());
        LocalDateTime now = LocalDateTime.now();
        UpdateWrapper<ReservationOrder> close = new UpdateWrapper<ReservationOrder>()
                .set("status", ORDER_STATUS_CANCELLED)
                .eq("id", orderId)
                .eq("status", ORDER_STATUS_UNPAID);
        if (expectedUserId != null) close.eq("user_id", expectedUserId);
        if (requireExpired) close.le("offer_expire_time", now);
        int cancelled = orderMapper.update(null, close);
        if (cancelled == 0) {
            return new ReleaseResult(ReleaseAction.SKIPPED, oldOrder, null);
        }

        updateRequestState(orderId, waitlistTerminalStatus, orderId, null);
        if (ReservationStatus.SOURCE_WAITLIST.equals(oldOrder.getSource())) {
            int waitlistChanged = waitlistMapper.update(null,
                    new UpdateWrapper<ReservationWaitlist>()
                            .set("status", waitlistTerminalStatus)
                            .eq("request_id", oldOrder.getId())
                            .eq("status", ReservationStatus.OFFERED));
            if (waitlistChanged != 1) {
                throw new IllegalStateException("候补订单与候补状态不一致: " + orderId);
            }
        }

        boolean canPromote = pass != null && pass.getEndTime() != null && pass.getEndTime().isAfter(now);
        if (canPromote) {
            ReservationOrder promoted = promoteFirstValidWaiter(oldOrder, pass, offerMinutes, now);
            if (promoted != null) {
                return new ReleaseResult(ReleaseAction.PROMOTED, oldOrder, promoted);
            }
        }

        if (pass == null) {
            throw new IllegalStateException("活动通行证不存在，无法恢复库存: " + oldOrder.getActivityPassId());
        }
        int stockRows = stockMapper.update(null,
                new UpdateWrapper<LimitedPassStock>()
                        .setSql("stock = stock + 1")
                        .eq("activity_pass_id", oldOrder.getActivityPassId()));
        if (stockRows != 1) {
            throw new IllegalStateException("活动通行证不存在，无法恢复库存: " + oldOrder.getActivityPassId());
        }
        reliableTaskRepository.enqueue(
                ReliableTaskRepository.RESTORE_REDIS_STOCK,
                "restore-redis-stock:" + orderId,
                JSONUtil.toJsonStr(oldOrder));
        return new ReleaseResult(ReleaseAction.RELEASED, oldOrder, null);
    }

    private ReservationOrder promoteFirstValidWaiter(ReservationOrder oldOrder,
                                                       LimitedPassStock pass,
                                                       int offerMinutes,
                                                       LocalDateTime now) {
        while (true) {
            ReservationWaitlist candidate = waitlistMapper.selectNextForUpdate(oldOrder.getActivityPassId());
            if (candidate == null) return null;

            ReservationOrder existing = orderMapper.selectOne(new QueryWrapper<ReservationOrder>()
                    .eq("activity_pass_id", candidate.getActivityPassId())
                    .eq("user_id", candidate.getUserId())
                    .in("status", 1, 2, 3, 5)
                    .last("LIMIT 1"));
            if (existing != null || (candidate.getWaitExpireTime() != null
                    && !candidate.getWaitExpireTime().isAfter(now))) {
                String reason = existing != null ? "ACTIVE_ORDER_EXISTS" : "WAIT_EXPIRED";
                waitlistMapper.update(null, new UpdateWrapper<ReservationWaitlist>()
                        .set("status", ReservationStatus.EXPIRED)
                        .set("invalid_reason", reason)
                        .eq("id", candidate.getId())
                        .eq("status", ReservationStatus.WAITING));
                updateRequestState(candidate.getRequestId(), ReservationStatus.EXPIRED, null, reason);
                continue;
            }

            LocalDateTime expiresAt = capOfferExpiry(now, offerMinutes, pass.getEndTime());
            int offered = waitlistMapper.update(null,
                    new UpdateWrapper<ReservationWaitlist>()
                            .set("status", ReservationStatus.OFFERED)
                            .set("offered_order_id", candidate.getRequestId())
                            .set("offer_expire_time", expiresAt)
                            .eq("id", candidate.getId())
                            .eq("status", ReservationStatus.WAITING));
            if (offered != 1) {
                throw new IllegalStateException("候补队首已被并发修改: " + candidate.getRequestId());
            }

            long oldVersion = oldOrder.getResourceVersion() == null ? 0L : oldOrder.getResourceVersion();
            long newVersion = oldVersion + 1;
            ReservationOrder promoted = new ReservationOrder()
                    .setId(candidate.getRequestId())
                    .setActivityPassId(candidate.getActivityPassId())
                    .setUserId(candidate.getUserId())
                    .setStatus(ORDER_STATUS_UNPAID)
                    .setSource(ReservationStatus.SOURCE_WAITLIST)
                    .setPromotionRound((oldOrder.getPromotionRound() == null ? 0 : oldOrder.getPromotionRound()) + 1)
                    .setOfferExpireTime(expiresAt)
                    .setResourceVersion(newVersion);
            orderMapper.insert(promoted);
            updateRequestState(candidate.getRequestId(), ReservationStatus.RESERVED, promoted.getId(), null);
            enqueueTimeout(promoted.getId(), expiresAt);

            ReservationClaimTransfer transfer = new ReservationClaimTransfer(
                    oldOrder.getActivityPassId(), oldOrder.getUserId(), oldOrder.getId(), oldVersion,
                    promoted.getUserId(), promoted.getId(), newVersion);
            reliableTaskRepository.enqueue(
                    ReliableTaskRepository.TRANSFER_RESERVATION_CLAIM,
                    "transfer-reservation:" + oldOrder.getId(),
                    JSONUtil.toJsonStr(transfer));
            if (candidate.getCreateTime() != null) {
                metrics.recordPromotionLatency(Duration.between(candidate.getCreateTime(), now));
            }
            return promoted;
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public boolean payUnpaidOrder(Long orderId, Long userId) {
        ReservationOrder order = orderMapper.selectById(orderId);
        if (order == null || !userId.equals(order.getUserId())) return false;
        int changed = orderMapper.update(null,
                new UpdateWrapper<ReservationOrder>()
                        .set("status", ORDER_STATUS_PAID)
                        .set("pay_time", LocalDateTime.now())
                        .eq("id", orderId)
                        .eq("user_id", userId)
                        .eq("status", ORDER_STATUS_UNPAID)
                        .gt("offer_expire_time", LocalDateTime.now()));
        if (changed != 1) return false;
        updateRequestState(orderId, ReservationStatus.PAID, orderId, null);
        if (ReservationStatus.SOURCE_WAITLIST.equals(order.getSource())) {
            int accepted = waitlistMapper.update(null,
                    new UpdateWrapper<ReservationWaitlist>()
                            .set("status", ReservationStatus.ACCEPTED)
                            .eq("request_id", orderId)
                            .eq("status", ReservationStatus.OFFERED));
            if (accepted != 1) throw new IllegalStateException("候补支付与候补状态不一致: " + orderId);
        }
        return true;
    }

    @Transactional(rollbackFor = Exception.class)
    public boolean cancelWaiting(Long requestId, Long userId) {
        int changed = waitlistMapper.update(null,
                new UpdateWrapper<ReservationWaitlist>()
                        .set("status", ReservationStatus.CANCELLED)
                        .eq("request_id", requestId)
                        .eq("user_id", userId)
                        .eq("status", ReservationStatus.WAITING));
        if (changed == 1) updateRequestState(requestId, ReservationStatus.CANCELLED, null, null);
        return changed == 1;
    }

    @Transactional(rollbackFor = Exception.class)
    public boolean expireWaiting(Long requestId) {
        int changed = waitlistMapper.update(null,
                new UpdateWrapper<ReservationWaitlist>()
                        .set("status", ReservationStatus.EXPIRED)
                        .set("invalid_reason", "ACTIVITY_ENDED")
                        .eq("request_id", requestId)
                        .eq("status", ReservationStatus.WAITING)
                        .le("wait_expire_time", LocalDateTime.now()));
        if (changed == 1) {
            updateRequestState(requestId, ReservationStatus.EXPIRED, null, "ACTIVITY_ENDED");
        }
        return changed == 1;
    }

    @Transactional(rollbackFor = Exception.class)
    public String markRequestState(Long requestId, String status, Long orderId, String error) {
        return updateRequestState(requestId, status, orderId, error);
    }

    /** Request projection is a monotonic state machine; stale MQ deliveries cannot overwrite a terminal result. */
    private String updateRequestState(Long requestId, String target, Long orderId, String error) {
        for (int attempt = 0; attempt < 2; attempt++) {
            ReservationRequest current = requestMapper.selectById(requestId);
            if (current == null) throw new IllegalStateException("预约请求事实不存在: " + requestId);
            String from = current.getStatus();
            if (!canTransition(from, target)) return from;
            int changed = requestMapper.update(null, new UpdateWrapper<ReservationRequest>()
                    .set("status", target)
                    .set("order_id", orderId)
                    .set("last_error", error)
                    .eq("request_id", requestId)
                    .eq("status", from));
            if (changed == 1) return target;
        }
        ReservationRequest latest = requestMapper.selectById(requestId);
        if (latest == null) throw new IllegalStateException("预约请求事实不存在: " + requestId);
        return latest.getStatus();
    }

    private boolean canTransition(String from, String target) {
        if (target.equals(from)) return true;
        if (ReservationStatus.PROCESSING.equals(from)) return true;
        if (ReservationStatus.WAITLISTED.equals(from)) {
            return ReservationStatus.RESERVED.equals(target)
                    || ReservationStatus.CANCELLED.equals(target)
                    || ReservationStatus.EXPIRED.equals(target);
        }
        if (ReservationStatus.RESERVED.equals(from)) {
            return ReservationStatus.PAID.equals(target)
                    || ReservationStatus.CANCELLED.equals(target)
                    || ReservationStatus.EXPIRED.equals(target);
        }
        return false;
    }

    private void enqueueTimeout(Long orderId, LocalDateTime expiresAt) {
        reliableTaskRepository.enqueueAt(
                ReliableTaskRepository.ORDER_TIMEOUT,
                "order-timeout:" + orderId,
                String.valueOf(orderId),
                expiresAt);
    }

    private LocalDateTime capOfferExpiry(LocalDateTime now, int offerMinutes, LocalDateTime activityEnd) {
        LocalDateTime configured = now.plusMinutes(Math.max(1, offerMinutes));
        return activityEnd != null && activityEnd.isBefore(configured) ? activityEnd : configured;
    }
}
