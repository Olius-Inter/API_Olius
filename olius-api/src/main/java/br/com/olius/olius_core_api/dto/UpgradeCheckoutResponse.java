package br.com.olius.olius_core_api.dto;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.util.UUID;

public record UpgradeCheckoutResponse(UUID chargeId,UUID planId,BigDecimal amount,String currency,
        Instant expiresAt,URI paymentUrl,UpgradeDetails upgrade) {
    public record UpgradeDetails(UUID cycleId,Instant cycleEndsAt,UUID fromPlanId,BigDecimal fromMonthlyPrice,
            BigDecimal targetMonthlyPrice,BigDecimal targetVolumeLimitLiters,int targetCollectionLimit,
            BigDecimal currentAvailableVolumeLiters,long currentAvailableCollectionSlots,
            BigDecimal availableVolumeLitersAfterUpgrade,long availableCollectionSlotsAfterUpgrade,
            Instant availabilityCalculatedAt) { }
}
