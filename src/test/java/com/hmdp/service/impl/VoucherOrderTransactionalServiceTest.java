package com.hmdp.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.SeckillVoucherMapper;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.reliable.ReliableTaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static com.hmdp.utils.RedisConstants.ORDER_STATUS_UNPAID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class VoucherOrderTransactionalServiceTest {

    private VoucherOrderMapper orderMapper;
    private SeckillVoucherMapper voucherMapper;
    private ReliableTaskRepository taskRepository;
    private VoucherOrderTransactionalService service;

    @BeforeEach
    void setUp() {
        orderMapper = mock(VoucherOrderMapper.class);
        voucherMapper = mock(SeckillVoucherMapper.class);
        taskRepository = mock(ReliableTaskRepository.class);
        service = new VoucherOrderTransactionalService(orderMapper, voucherMapper, taskRepository);
    }

    @Test
    void createOrderPersistsOrderAndTimeoutTaskAfterConditionalStockDeduction() {
        VoucherOrder order = new VoucherOrder().setId(10L).setUserId(20L).setVoucherId(30L);
        when(voucherMapper.update(isNull(), any(UpdateWrapper.class))).thenReturn(1);
        when(orderMapper.insert(order)).thenReturn(1);

        VoucherOrderTransactionalService.CreateResult result = service.createOrder(order);

        assertEquals(VoucherOrderTransactionalService.CreateResult.CREATED, result);
        assertEquals(ORDER_STATUS_UNPAID, order.getStatus());
        verify(orderMapper).insert(order);
        verify(taskRepository).enqueue(
                ReliableTaskRepository.ORDER_TIMEOUT, "order-timeout:10", "10");
    }

    @Test
    void soldOutDoesNotInsertOrderOrTimeoutTask() {
        VoucherOrder order = new VoucherOrder().setId(10L).setUserId(20L).setVoucherId(30L);
        when(voucherMapper.update(isNull(), any(UpdateWrapper.class))).thenReturn(0);

        VoucherOrderTransactionalService.CreateResult result = service.createOrder(order);

        assertEquals(VoucherOrderTransactionalService.CreateResult.SOLD_OUT, result);
        verifyNoInteractions(orderMapper, taskRepository);
    }

    @Test
    void cancelCreatesRedisCompensationOnlyAfterDbStockWasRestored() {
        VoucherOrder order = new VoucherOrder().setId(10L).setUserId(20L).setVoucherId(30L)
                .setStatus(ORDER_STATUS_UNPAID);
        when(orderMapper.selectById(10L)).thenReturn(order);
        when(orderMapper.update(isNull(), any(UpdateWrapper.class))).thenReturn(1);
        when(voucherMapper.update(isNull(), any(UpdateWrapper.class))).thenReturn(1);

        VoucherOrder cancelled = service.cancelUnpaidOrder(10L);

        assertSame(order, cancelled);
        verify(taskRepository).enqueue(eq(ReliableTaskRepository.RESTORE_REDIS_STOCK),
                eq("restore-redis-stock:10"), contains("\"id\":10"));
    }

    @Test
    void cancelFailsTransactionWhenVoucherStockRowIsMissing() {
        VoucherOrder order = new VoucherOrder().setId(10L).setVoucherId(30L).setStatus(ORDER_STATUS_UNPAID);
        when(orderMapper.selectById(10L)).thenReturn(order);
        when(orderMapper.update(isNull(), any(UpdateWrapper.class))).thenReturn(1);
        when(voucherMapper.update(isNull(), any(UpdateWrapper.class))).thenReturn(0);

        assertThrows(IllegalStateException.class, () -> service.cancelUnpaidOrder(10L));
        verifyNoInteractions(taskRepository);
    }
}
