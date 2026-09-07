package com.citypass.controller;

import com.citypass.dto.Result;
import com.citypass.service.IReservationService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;

/** 城市活动限量预约接口。 */
@RestController
@RequestMapping("/reservations")
public class ReservationController {

    @Resource
    private IReservationService reservationService;

    @PostMapping("/{passId}")
    public Result reserve(@PathVariable Long passId,
                          @RequestParam(defaultValue = "true") boolean acceptWaitlist) {
        return reservationService.reserve(passId, acceptWaitlist);
    }

    @GetMapping("/requests/{requestId}")
    public Result result(@PathVariable Long requestId) {
        return reservationService.getReservationResult(requestId);
    }

    @PutMapping("/{orderId}/pay")
    public Result pay(@PathVariable Long orderId) {
        return reservationService.payOrder(orderId);
    }

    @PutMapping("/{orderId}/cancel")
    public Result cancel(@PathVariable Long orderId) {
        return reservationService.cancelOrder(orderId);
    }

    @DeleteMapping("/waitlist/{requestId}")
    public Result leaveWaitlist(@PathVariable Long requestId) {
        return reservationService.cancelWaitlist(requestId);
    }
}
