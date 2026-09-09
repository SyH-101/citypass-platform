package com.citypass.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.citypass.entity.LimitedPassStock;
import com.citypass.entity.ReservationOrder;
import com.citypass.entity.ReservationWaitlist;
import com.citypass.entity.ReservationRequest;
import com.citypass.mapper.LimitedPassStockMapper;
import com.citypass.mapper.ReservationOrderMapper;
import com.citypass.mapper.ReservationWaitlistMapper;
import com.citypass.mapper.ReservationRequestMapper;
import com.citypass.reliable.ReliableTaskMetrics;
import com.citypass.reliable.ReliableTaskRepository;
import com.citypass.utils.ReservationStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static com.citypass.utils.RedisConstants.ORDER_STATUS_UNPAID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ReservationTransactionalServiceTest {

    private ReservationOrderMapper orderMapper;
    private ReservationWaitlistMapper waitlistMapper;
    private LimitedPassStockMapper stockMapper;
    private ReliableTaskRepository taskRepository;
    private ReservationRequestMapper requestMapper;
    private ReliableTaskMetrics metrics;
    private ReservationTransactionalService service;

    @BeforeEach
    void setUp() {
        orderMapper = mock(ReservationOrderMapper.class);
        waitlistMapper = mock(ReservationWaitlistMapper.class);
        stockMapper = mock(LimitedPassStockMapper.class);
        taskRepository = mock(ReliableTaskRepository.class);
        requestMapper = mock(ReservationRequestMapper.class);
        metrics = mock(ReliableTaskMetrics.class);
        when(requestMapper.selectById(anyLong())).thenAnswer(invocation -> new ReservationRequest()
                .setRequestId(invocation.getArgument(0)).setStatus(ReservationStatus.PROCESSING));
        when(requestMapper.update(isNull(), any(UpdateWrapper.class))).thenReturn(1);
        service = new ReservationTransactionalService(
                orderMapper, waitlistMapper, stockMapper, requestMapper, taskRepository, metrics);
    }

    @Test
    void createOrderPersistsOrderAndTimeoutTaskAfterConditionalStockDeduction() {
        ReservationOrder order = new ReservationOrder().setId(10L).setUserId(20L).setActivityPassId(30L);
        when(stockMapper.update(isNull(), any(UpdateWrapper.class))).thenReturn(1);
        when(orderMapper.insert(order)).thenReturn(1);

        ReservationTransactionalService.CreateResult result = service.createOrder(order, 15);

        assertEquals(ReservationTransactionalService.CreateResult.CREATED, result);
        assertEquals(ORDER_STATUS_UNPAID, order.getStatus());
        assertEquals(ReservationStatus.SOURCE_DIRECT, order.getSource());
        assertNotNull(order.getOfferExpireTime());
        verify(orderMapper).insert(order);
        verify(taskRepository).enqueueAt(
                eq(ReliableTaskRepository.ORDER_TIMEOUT), eq("order-timeout:10"), eq("10"), any(LocalDateTime.class));
    }

    @Test
    void soldOutDoesNotInsertOrderOrTimeoutTask() {
        ReservationOrder order = new ReservationOrder().setId(10L).setUserId(20L).setActivityPassId(30L);
        when(stockMapper.update(isNull(), any(UpdateWrapper.class))).thenReturn(0);
        when(stockMapper.selectById(30L)).thenReturn(activePass().setStock(0));

        ReservationTransactionalService.CreateResult result = service.createOrder(order, 15);

        assertEquals(ReservationTransactionalService.CreateResult.SOLD_OUT, result);
        verifyNoInteractions(orderMapper, waitlistMapper, taskRepository);
    }

    @Test
    void activityOutsideReservationWindowDoesNotCreateOrder() {
        ReservationOrder order = new ReservationOrder().setId(10L).setUserId(20L).setActivityPassId(30L);
        LimitedPassStock future = activePass()
                .setBeginTime(LocalDateTime.now().plusHours(1))
                .setEndTime(LocalDateTime.now().plusHours(2));
        when(stockMapper.update(isNull(), any(UpdateWrapper.class))).thenReturn(0);
        when(stockMapper.selectById(30L)).thenReturn(future);

        ReservationTransactionalService.CreateResult result = service.createOrder(order, 15);

        assertEquals(ReservationTransactionalService.CreateResult.NOT_STARTED, result);
        verifyNoInteractions(orderMapper, waitlistMapper, taskRepository);
    }

    @Test
    void releaseRestoresStockOnlyWhenWaitlistIsEmpty() {
        ReservationOrder old = directOrder();
        when(orderMapper.selectById(10L)).thenReturn(old);
        when(orderMapper.update(isNull(), any(UpdateWrapper.class))).thenReturn(1);
        when(stockMapper.selectByIdForUpdate(30L)).thenReturn(activePass());
        when(waitlistMapper.selectNextForUpdate(30L)).thenReturn(null);
        when(stockMapper.update(isNull(), any(UpdateWrapper.class))).thenReturn(1);

        ReservationTransactionalService.ReleaseResult result = service.releaseOrPromote(
                10L, 20L, ReservationStatus.CANCELLED, 15);

        assertEquals(ReservationTransactionalService.ReleaseAction.RELEASED, result.getAction());
        verify(taskRepository).enqueue(eq(ReliableTaskRepository.RESTORE_REDIS_STOCK),
                eq("restore-redis-stock:10"), contains("\"id\":10"));
    }

    @Test
    void releaseTransfersSlotToFirstWaiterWithoutChangingStock() {
        ReservationOrder old = directOrder();
        ReservationWaitlist first = new ReservationWaitlist()
                .setId(1L).setRequestId(99L).setActivityPassId(30L).setUserId(88L)
                .setStatus(ReservationStatus.WAITING);
        when(orderMapper.selectById(10L)).thenReturn(old);
        when(orderMapper.update(isNull(), any(UpdateWrapper.class))).thenReturn(1);
        when(stockMapper.selectByIdForUpdate(30L)).thenReturn(activePass());
        when(waitlistMapper.selectNextForUpdate(30L)).thenReturn(first);
        when(waitlistMapper.update(isNull(), any(UpdateWrapper.class))).thenReturn(1);
        when(orderMapper.insert(any(ReservationOrder.class))).thenReturn(1);

        ReservationTransactionalService.ReleaseResult result = service.releaseOrPromote(
                10L, null, ReservationStatus.EXPIRED, 15);

        assertEquals(ReservationTransactionalService.ReleaseAction.PROMOTED, result.getAction());
        assertEquals(99L, result.getPromotedOrder().getId());
        assertEquals(1, result.getPromotedOrder().getPromotionRound());
        verify(stockMapper, never()).update(isNull(), any(UpdateWrapper.class));
        verify(taskRepository).enqueueAt(eq(ReliableTaskRepository.ORDER_TIMEOUT),
                eq("order-timeout:99"), eq("99"), any(LocalDateTime.class));
        verify(taskRepository).enqueue(eq(ReliableTaskRepository.TRANSFER_RESERVATION_CLAIM),
                eq("transfer-reservation:10"), contains("\"newOrderId\":99"));
    }

    @Test
    void payingPromotedOrderAlsoAcceptsWaitlist() {
        ReservationOrder promoted = new ReservationOrder()
                .setId(99L).setUserId(88L).setActivityPassId(30L)
                .setStatus(ORDER_STATUS_UNPAID)
                .setSource(ReservationStatus.SOURCE_WAITLIST)
                .setOfferExpireTime(LocalDateTime.now().plusMinutes(10));
        when(orderMapper.selectById(99L)).thenReturn(promoted);
        when(orderMapper.update(isNull(), any(UpdateWrapper.class))).thenReturn(1);
        when(waitlistMapper.update(isNull(), any(UpdateWrapper.class))).thenReturn(1);

        assertTrue(service.payUnpaidOrder(99L, 88L));
        verify(waitlistMapper).update(isNull(), any(UpdateWrapper.class));
    }

    @Test
    void staleCreateDeliveryCannotRegressTerminalRequestState() {
        reset(requestMapper);
        when(requestMapper.selectById(10L)).thenReturn(new ReservationRequest()
                .setRequestId(10L).setStatus(ReservationStatus.CANCELLED));

        String effective = service.markRequestState(
                10L, ReservationStatus.RESERVED, 10L, null);

        assertEquals(ReservationStatus.CANCELLED, effective);
        verify(requestMapper, never()).update(isNull(), any(UpdateWrapper.class));
    }

    private ReservationOrder directOrder() {
        return new ReservationOrder().setId(10L).setUserId(20L).setActivityPassId(30L)
                .setStatus(ORDER_STATUS_UNPAID)
                .setSource(ReservationStatus.SOURCE_DIRECT)
                .setPromotionRound(0)
                .setOfferExpireTime(LocalDateTime.now().minusMinutes(1));
    }

    private LimitedPassStock activePass() {
        return new LimitedPassStock().setActivityPassId(30L)
                .setBeginTime(LocalDateTime.now().minusHours(1))
                .setEndTime(LocalDateTime.now().plusDays(1));
    }
}
