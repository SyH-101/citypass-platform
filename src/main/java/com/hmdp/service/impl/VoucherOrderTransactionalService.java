package com.hmdp.service.impl;

import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.SeckillVoucherMapper;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.reliable.ReliableTaskRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

import static com.hmdp.utils.RedisConstants.ORDER_STATUS_CANCELLED;
import static com.hmdp.utils.RedisConstants.ORDER_STATUS_PAID;
import static com.hmdp.utils.RedisConstants.ORDER_STATUS_UNPAID;

/**
 * 订单数据库事务边界。单独成 Bean，避免同类方法调用导致 @Transactional 失效。
 */
@Service
public class VoucherOrderTransactionalService {

    public enum CreateResult { CREATED, SOLD_OUT }

    private final VoucherOrderMapper voucherOrderMapper;
    private final SeckillVoucherMapper seckillVoucherMapper;
    private final ReliableTaskRepository reliableTaskRepository;

    public VoucherOrderTransactionalService(VoucherOrderMapper voucherOrderMapper,
                                            SeckillVoucherMapper seckillVoucherMapper,
                                            ReliableTaskRepository reliableTaskRepository) {
        this.voucherOrderMapper = voucherOrderMapper;
        this.seckillVoucherMapper = seckillVoucherMapper;
        this.reliableTaskRepository = reliableTaskRepository;
    }

    /** DB 条件扣库存、订单插入、关单任务落库必须同时成功或同时回滚。 */
    @Transactional(rollbackFor = Exception.class)
    public CreateResult createOrder(VoucherOrder order) throws DuplicateKeyException {
        int stockRows = seckillVoucherMapper.update(null,
                new UpdateWrapper<com.hmdp.entity.SeckillVoucher>()
                        .setSql("stock = stock - 1")
                        .eq("voucher_id", order.getVoucherId())
                        .gt("stock", 0));
        if (stockRows == 0) {
            return CreateResult.SOLD_OUT;
        }
        order.setStatus(ORDER_STATUS_UNPAID);
        voucherOrderMapper.insert(order);
        reliableTaskRepository.enqueue(
                ReliableTaskRepository.ORDER_TIMEOUT,
                "order-timeout:" + order.getId(),
                String.valueOf(order.getId()));
        return CreateResult.CREATED;
    }

    /** 订单状态、DB 库存和 Redis 补偿任务处于同一个本地事务。 */
    @Transactional(rollbackFor = Exception.class)
    public VoucherOrder cancelUnpaidOrder(Long orderId) {
        VoucherOrder order = voucherOrderMapper.selectById(orderId);
        if (order == null || !Integer.valueOf(ORDER_STATUS_UNPAID).equals(order.getStatus())) {
            return null;
        }
        int changed = voucherOrderMapper.update(null,
                new UpdateWrapper<VoucherOrder>()
                        .set("status", ORDER_STATUS_CANCELLED)
                        .eq("id", orderId)
                        .eq("status", ORDER_STATUS_UNPAID));
        if (changed == 0) {
            return null;
        }
        int stockRows = seckillVoucherMapper.update(null,
                new UpdateWrapper<com.hmdp.entity.SeckillVoucher>()
                        .setSql("stock = stock + 1")
                        .eq("voucher_id", order.getVoucherId()));
        if (stockRows != 1) {
            throw new IllegalStateException("秒杀券不存在，无法回补 DB 库存: " + order.getVoucherId());
        }
        reliableTaskRepository.enqueue(
                ReliableTaskRepository.RESTORE_REDIS_STOCK,
                "restore-redis-stock:" + orderId,
                JSONUtil.toJsonStr(order));
        return order;
    }

    public boolean payUnpaidOrder(Long orderId, Long userId) {
        int changed = voucherOrderMapper.update(null,
                new UpdateWrapper<VoucherOrder>()
                        .set("status", ORDER_STATUS_PAID)
                        .set("pay_time", LocalDateTime.now())
                        .eq("id", orderId)
                        .eq("user_id", userId)
                        .eq("status", ORDER_STATUS_UNPAID));
        return changed == 1;
    }
}
