package com.citypass.reliable;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Redis 名额归属转移任务载荷。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ReservationClaimTransfer {
    private Long activityPassId;
    private Long oldUserId;
    private Long oldOrderId;
    private Long oldResourceVersion;
    private Long newUserId;
    private Long newOrderId;
    private Long newResourceVersion;
}
