package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.mq.OrderMessagePublisher;
import com.hmdp.mq.SeckillTxContext;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.SeckillMode;
import com.hmdp.utils.UserHolder;
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
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/** 秒杀下单编排：Redis/MQ 负责削峰，数据库事务服务负责最终一致的订单账本。 */
@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder>
        implements IVoucherOrderService {

    @Resource
    private RedisIdWorker redisIdWorker;
    @Resource
    private RedissonClient redissonClient;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private OrderMessagePublisher rocketMQProducer;
    @Resource
    private VoucherOrderTransactionalService transactionalService;

    @Value("${seckill.mode:A}")
    private String seckillMode;

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT = script("seckill.lua");
    private static final DefaultRedisScript<Long> SECKILL_STOCK_ONLY_SCRIPT = script("seckill-stock-only.lua");
    private static final DefaultRedisScript<Long> SECKILL_CLAIM_SCRIPT = script("seckill-claim.lua");
    private static final DefaultRedisScript<Long> SECKILL_ROLLBACK_SCRIPT = script("seckill-rollback.lua");

    /** 仅供压测教学切换；生产必须保持 FULL。 */
    public static final String ONE_ORDER_PROTECTION_KEY = "seckill:test:protection";

    private static DefaultRedisScript<Long> script(String path) {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource(path));
        script.setResultType(Long.class);
        return script;
    }

    private String oneOrderProtection() {
        String mode = stringRedisTemplate.opsForValue().get(ONE_ORDER_PROTECTION_KEY);
        return mode == null || mode.isEmpty() ? "FULL" : mode.trim().toUpperCase();
    }

    @Override
    public void cancelTimeoutOrder(Long orderId) {
        VoucherOrder cancelled = transactionalService.cancelUnpaidOrder(orderId);
        if (cancelled != null) {
            log.info("订单 {} 超时取消；DB 库存已回补，Redis 幂等补偿任务已入库", orderId);
        }
    }

    @Override
    public Result payOrder(Long orderId) {
        if (orderId == null || UserHolder.getUser() == null) {
            return Result.fail("订单号不能为空或用户未登录");
        }
        boolean paid = transactionalService.payUnpaidOrder(orderId, UserHolder.getUser().getId());
        return paid ? Result.ok("支付成功") : Result.fail("订单不存在、无权操作或状态不允许支付");
    }

    /** MQ 重复投递安全：用户锁降低竞争，DB 唯一索引兜底；扣库存、插订单、写超时任务同事务。 */
    @Override
    public void createOrderFromMQ(VoucherOrder order) {
        Long userId = order.getUserId();
        Long voucherId = order.getVoucherId();
        Long orderId = order.getId();
        if (userId == null || voucherId == null || orderId == null) {
            throw new IllegalArgumentException("订单消息缺少 id/userId/voucherId");
        }

        boolean modeB = SeckillMode.B.equalsIgnoreCase(order.getSeckillMode());
        boolean early = "EARLY".equals(oneOrderProtection());
        RLock lock = redissonClient.getLock("lock:order:" + userId);
        boolean locked = early || lock.tryLock();
        if (!locked) {
            throw new IllegalStateException("获取用户下单锁失败，交给 MQ 重试: " + orderId);
        }

        try {
            VoucherOrder existing = lambdaQuery()
                    .eq(VoucherOrder::getUserId, userId)
                    .eq(VoucherOrder::getVoucherId, voucherId)
                    .last("LIMIT 1")
                    .one();
            if (existing != null) {
                writeQueueStatus(orderId,
                        orderId.equals(existing.getId()) ? SeckillMode.QUEUE_SUCCESS : SeckillMode.QUEUE_FAIL_REPEAT);
                return;
            }

            if (modeB) {
                Long claim = stringRedisTemplate.execute(
                        SECKILL_CLAIM_SCRIPT,
                        Collections.emptyList(),
                        voucherId.toString(), userId.toString(), orderId.toString());
                if (claim == null) {
                    throw new IllegalStateException("Redis claim 返回空");
                }
                if (claim == 1L) {
                    writeQueueStatus(orderId, SeckillMode.QUEUE_FAIL_STOCK);
                    return;
                }
                if (claim == 2L) {
                    writeQueueStatus(orderId, SeckillMode.QUEUE_FAIL_REPEAT);
                    return;
                }
            }

            VoucherOrderTransactionalService.CreateResult result;
            try {
                result = transactionalService.createOrder(order);
            } catch (DuplicateKeyException duplicate) {
                VoucherOrder winner = lambdaQuery()
                        .eq(VoucherOrder::getUserId, userId)
                        .eq(VoucherOrder::getVoucherId, voucherId)
                        .last("LIMIT 1")
                        .one();
                writeQueueStatus(orderId,
                        winner != null && orderId.equals(winner.getId())
                                ? SeckillMode.QUEUE_SUCCESS : SeckillMode.QUEUE_FAIL_REPEAT);
                return;
            }

            if (result == VoucherOrderTransactionalService.CreateResult.SOLD_OUT) {
                rollbackReservation(voucherId, userId, orderId);
                writeQueueStatus(orderId, SeckillMode.QUEUE_FAIL_STOCK);
                return;
            }
            writeQueueStatus(orderId, SeckillMode.QUEUE_SUCCESS);
        } catch (RuntimeException e) {
            log.error("订单异步落库失败，等待 MQ 重试, orderId={}", orderId, e);
            throw e;
        } finally {
            if (!early && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    @Override
    public long executeSeckillLocalTransaction(Long voucherId, Long userId, Long orderId) {
        DefaultRedisScript<Long> selected = "EARLY".equals(oneOrderProtection())
                ? SECKILL_STOCK_ONLY_SCRIPT : SECKILL_SCRIPT;
        Long result = stringRedisTemplate.execute(
                selected,
                Collections.emptyList(),
                voucherId.toString(), userId.toString(), orderId.toString(),
                String.valueOf(SECKILL_TXN_TTL_SECONDS));
        return result == null ? -1L : result;
    }

    @Override
    public boolean hasSeckillReservation(Long voucherId, Long userId, Long orderId) {
        if (Boolean.TRUE.equals(stringRedisTemplate.hasKey(SECKILL_TXN_KEY + orderId))) {
            return true;
        }
        String owner = stringRedisTemplate.opsForValue().get(
                SECKILL_CLAIM_KEY + voucherId + ":" + userId);
        return orderId.toString().equals(owner);
    }

    @Override
    public Result seckillVoucher(Long voucherId) {
        if (voucherId == null || UserHolder.getUser() == null) {
            return Result.fail("参数错误或用户未登录");
        }
        return SeckillMode.B.equalsIgnoreCase(seckillMode)
                ? seckillVoucherModeB(voucherId) : seckillVoucherModeA(voucherId);
    }

    private Result seckillVoucherModeA(Long voucherId) {
        Long userId = UserHolder.getUser().getId();
        long orderId = redisIdWorker.nextId("order");
        VoucherOrder order = newOrder(orderId, userId, voucherId, SeckillMode.A);
        initializeQueue(orderId, userId);

        SeckillTxContext ctx = new SeckillTxContext(order);
        try {
            SendResult sent = rocketMQProducer.sendOrderCreateInTransaction(order, ctx);
            if (sent.getSendStatus() != SendStatus.SEND_OK) {
                writeQueueStatus(orderId, SeckillMode.QUEUE_FAIL_SYSTEM);
                return Result.fail("系统繁忙，请稍后重试");
            }
        } catch (Exception e) {
            log.error("事务消息发送异常, orderId={}", orderId, e);
            writeQueueStatus(orderId, SeckillMode.QUEUE_FAIL_SYSTEM);
            return Result.fail("系统繁忙，请稍后重试");
        }

        long result = ctx.getLuaResult();
        if (result == 0 || result == -1) {
            return Result.ok(orderId);
        }
        writeQueueStatus(orderId, result == 1 ? SeckillMode.QUEUE_FAIL_STOCK : SeckillMode.QUEUE_FAIL_REPEAT);
        return Result.fail(result == 1 ? "库存不足" : "不能重复下单");
    }

    private Result seckillVoucherModeB(Long voucherId) {
        Long userId = UserHolder.getUser().getId();
        long orderId = redisIdWorker.nextId("order");
        VoucherOrder order = newOrder(orderId, userId, voucherId, SeckillMode.B);
        initializeQueue(orderId, userId);
        try {
            SendResult sent = rocketMQProducer.sendOrderCreate(order);
            if (sent.getSendStatus() != SendStatus.SEND_OK) {
                writeQueueStatus(orderId, SeckillMode.QUEUE_FAIL_SYSTEM);
                return Result.fail("系统繁忙，请稍后重试");
            }
        } catch (Exception e) {
            log.error("普通消息发送异常, orderId={}", orderId, e);
            writeQueueStatus(orderId, SeckillMode.QUEUE_FAIL_SYSTEM);
            return Result.fail("系统繁忙，请稍后重试");
        }
        return Result.ok(orderId);
    }

    @Override
    public Result getSeckillResult(Long orderId) {
        if (orderId == null || UserHolder.getUser() == null) {
            return Result.fail("订单号不能为空或用户未登录");
        }
        Long currentUserId = UserHolder.getUser().getId();
        String owner = stringRedisTemplate.opsForValue().get(SECKILL_QUEUE_OWNER_KEY + orderId);
        if (owner != null && !currentUserId.toString().equals(owner)) {
            return Result.fail("无权查询该订单");
        }

        Map<String, Object> data = new HashMap<>(4);
        data.put("orderId", orderId);
        String status = stringRedisTemplate.opsForValue().get(SECKILL_QUEUE_KEY + orderId);
        if (status != null) {
            data.put("status", status);
            return Result.ok(data);
        }

        VoucherOrder order = getById(orderId);
        if (order != null) {
            if (!currentUserId.equals(order.getUserId())) {
                return Result.fail("无权查询该订单");
            }
            data.put("status", SeckillMode.QUEUE_SUCCESS);
            data.put("orderStatus", order.getStatus());
        } else {
            data.put("status", "NOT_FOUND");
        }
        return Result.ok(data);
    }

    private VoucherOrder newOrder(long id, Long userId, Long voucherId, String mode) {
        return new VoucherOrder().setId(id).setUserId(userId).setVoucherId(voucherId).setSeckillMode(mode);
    }

    private void initializeQueue(Long orderId, Long userId) {
        stringRedisTemplate.opsForValue().set(
                SECKILL_QUEUE_OWNER_KEY + orderId, userId.toString(),
                SECKILL_QUEUE_TTL_MINUTES, TimeUnit.MINUTES);
        writeQueueStatus(orderId, SeckillMode.QUEUE_WAITING);
    }

    private void writeQueueStatus(Long orderId, String status) {
        stringRedisTemplate.opsForValue().set(
                SECKILL_QUEUE_KEY + orderId, status,
                SECKILL_QUEUE_TTL_MINUTES, TimeUnit.MINUTES);
    }

    private void rollbackReservation(Long voucherId, Long userId, Long orderId) {
        Long result = stringRedisTemplate.execute(
                SECKILL_ROLLBACK_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString(), orderId.toString());
        if (result != null && result < 0) {
            log.warn("Redis 库存 key 不存在，预扣回滚交给对账修复, orderId={}", orderId);
        }
    }
}
