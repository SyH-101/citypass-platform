package com.citypass.service.impl;

import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.citypass.entity.LimitedPassStock;
import com.citypass.entity.ReservationOrder;
import com.citypass.entity.ReservationWaitlist;
import com.citypass.mapper.LimitedPassStockMapper;
import com.citypass.mapper.ReservationOrderMapper;
import com.citypass.mapper.ReservationWaitlistMapper;
import com.citypass.reliable.ReliableTaskRepository;
import com.citypass.reliable.ReservationClaimTransfer;
import com.citypass.utils.ReservationStatus;
import lombok.AllArgsConstructor;
import lombok.Data;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

import static com.citypass.utils.RedisConstants.ORDER_STATUS_CANCELLED;
import static com.citypass.utils.RedisConstants.ORDER_STATUS_PAID;
import static com.citypass.utils.RedisConstants.ORDER_STATUS_UNPAID;

/** 预约订单、候补状态与可靠任务的数据库事务边界。 */
@Service
public class ReservationTransactionalService {

    public enum CreateResult { CREATED, SOLD_OUT, NOT_STARTED, ENDED, UNAVAILABLE }
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
    private final ReliableTaskRepository reliableTaskRepository;

    public ReservationTransactionalService(ReservationOrderMapper orderMapper,
                                            ReservationWaitlistMapper waitlistMapper,
                                            LimitedPassStockMapper stockMapper,
                                            ReliableTaskRepository reliableTaskRepository) {
        this.orderMapper = orderMapper;
        this.waitlistMapper = waitlistMapper;
        this.stockMapper = stockMapper;
        this.reliableTaskRepository = reliableTaskRepository;
    }

    /** DB 条件扣库存、订单插入、关单任务落库必须同时成功或同时回滚。 */
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
            if (current == null) {
                return CreateResult.UNAVAILABLE;
            }
            if (current.getBeginTime() != null && current.getBeginTime().isAfter(now)) {
                return CreateResult.NOT_STARTED;
            }
            if (current.getEndTime() == null || !current.getEndTime().isAfter(now)) {
                return CreateResult.ENDED;
            }
            return CreateResult.SOLD_OUT;
        }
        order.setStatus(ORDER_STATUS_UNPAID);
        order.setSource(order.getSource() == null ? ReservationStatus.SOURCE_DIRECT : order.getSource());
        order.setPromotionRound(order.getPromotionRound() == null ? 0 : order.getPromotionRound());
        order.setOfferExpireTime(now.plusMinutes(offerMinutes));
        orderMapper.insert(order);
        enqueueTimeout(order.getId());
        return CreateResult.CREATED;
    }

    /** 候补记录先落 MySQL；自增主键是稳定、公平且可恢复的排队顺序。 */
    @Transactional(rollbackFor = Exception.class)
    public void createWaitlist(ReservationWaitlist waitlist) throws DuplicateKeyException {
        waitlistMapper.insert(waitlist.setStatus(ReservationStatus.WAITING));
    }

    /**
     * 释放未支付名额并优先补位。补位时名额只是换人，DB 库存不增不减；
     * 只有候补为空、活动结束或达到最大轮次时才真正回补库存。
     */
    @Transactional(rollbackFor = Exception.class)
    public ReleaseResult releaseOrPromote(Long orderId,
                                          Long expectedUserId,
                                          String waitlistTerminalStatus,
                                          int offerMinutes) {
        ReservationOrder oldOrder = orderMapper.selectById(orderId);
        if (oldOrder == null || !Integer.valueOf(ORDER_STATUS_UNPAID).equals(oldOrder.getStatus())) {
            return new ReleaseResult(ReleaseAction.SKIPPED, oldOrder, null);
        }
        if (expectedUserId != null && !expectedUserId.equals(oldOrder.getUserId())) {
            return new ReleaseResult(ReleaseAction.DENIED, oldOrder, null);
        }

        int cancelled = orderMapper.update(null,
                new UpdateWrapper<ReservationOrder>()
                        .set("status", ORDER_STATUS_CANCELLED)
                        .eq("id", orderId)
                        .eq("status", ORDER_STATUS_UNPAID));
        if (cancelled == 0) {
            return new ReleaseResult(ReleaseAction.SKIPPED, oldOrder, null);
        }

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

        int currentRound = oldOrder.getPromotionRound() == null ? 0 : oldOrder.getPromotionRound();
        LimitedPassStock pass = stockMapper.selectById(oldOrder.getActivityPassId());
        boolean canPromote = pass != null
                && pass.getEndTime() != null
                && pass.getEndTime().isAfter(LocalDateTime.now());

        if (canPromote) {
            ReservationWaitlist candidate = waitlistMapper.selectNextForUpdate(oldOrder.getActivityPassId());
            if (candidate != null) {
                LocalDateTime expiresAt = LocalDateTime.now().plusMinutes(offerMinutes);
                int offered = waitlistMapper.update(null,
                        new UpdateWrapper<ReservationWaitlist>()
                                .set("status", ReservationStatus.OFFERED)
                                .set("offered_order_id", candidate.getRequestId())
                                .set("offer_expire_time", expiresAt)
                                .eq("id", candidate.getId())
                                .eq("status", ReservationStatus.WAITING));
                if (offered != 1) {
                    throw new IllegalStateException("候补记录已被并发修改: " + candidate.getRequestId());
                }

                ReservationOrder promoted = new ReservationOrder()
                        .setId(candidate.getRequestId())
                        .setActivityPassId(candidate.getActivityPassId())
                        .setUserId(candidate.getUserId())
                        .setStatus(ORDER_STATUS_UNPAID)
                        .setSource(ReservationStatus.SOURCE_WAITLIST)
                        .setPromotionRound(currentRound + 1)
                        .setOfferExpireTime(expiresAt);
                orderMapper.insert(promoted);
                enqueueTimeout(promoted.getId());

                ReservationClaimTransfer transfer = new ReservationClaimTransfer(
                        oldOrder.getActivityPassId(), oldOrder.getUserId(), oldOrder.getId(),
                        promoted.getUserId(), promoted.getId());
                reliableTaskRepository.enqueue(
                        ReliableTaskRepository.TRANSFER_RESERVATION_CLAIM,
                        "transfer-reservation:" + oldOrder.getId(),
                        JSONUtil.toJsonStr(transfer));
                return new ReleaseResult(ReleaseAction.PROMOTED, oldOrder, promoted);
            }
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

    @Transactional(rollbackFor = Exception.class)
    public boolean payUnpaidOrder(Long orderId, Long userId) {
        ReservationOrder order = orderMapper.selectById(orderId);
        if (order == null || !userId.equals(order.getUserId())) {
            return false;
        }
        int changed = orderMapper.update(null,
                new UpdateWrapper<ReservationOrder>()
                        .set("status", ORDER_STATUS_PAID)
                        .set("pay_time", LocalDateTime.now())
                        .eq("id", orderId)
                        .eq("user_id", userId)
                        .eq("status", ORDER_STATUS_UNPAID)
                        .gt("offer_expire_time", LocalDateTime.now()));
        if (changed != 1) {
            return false;
        }
        if (ReservationStatus.SOURCE_WAITLIST.equals(order.getSource())) {
            int accepted = waitlistMapper.update(null,
                    new UpdateWrapper<ReservationWaitlist>()
                            .set("status", ReservationStatus.ACCEPTED)
                            .eq("request_id", orderId)
                            .eq("status", ReservationStatus.OFFERED));
            if (accepted != 1) {
                throw new IllegalStateException("候补支付与候补状态不一致: " + orderId);
            }
        }
        return true;
    }

    @Transactional(rollbackFor = Exception.class)
    public boolean cancelWaiting(Long requestId, Long userId) {
        return waitlistMapper.update(null,
                new UpdateWrapper<ReservationWaitlist>()
                        .set("status", ReservationStatus.CANCELLED)
                        .eq("request_id", requestId)
                        .eq("user_id", userId)
                        .eq("status", ReservationStatus.WAITING)) == 1;
    }

    private void enqueueTimeout(Long orderId) {
        reliableTaskRepository.enqueue(
                ReliableTaskRepository.ORDER_TIMEOUT,
                "order-timeout:" + orderId,
                String.valueOf(orderId));
    }
}
