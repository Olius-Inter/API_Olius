package br.com.olius.olius_core_api.dto;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class FinancialRecoveryContracts {
    private FinancialRecoveryContracts() { }
    public record ChargeAction(@NotBlank @Size(min=10,max=240) @Pattern(regexp="[^\\p{Cntrl}]+") String reason) { }
    public record RefundAction(@NotBlank @Size(max=255) @Pattern(regexp="re_[A-Za-z0-9_]+") String providerRefundId,
        @NotBlank @Size(min=10,max=240) @Pattern(regexp="[^\\p{Cntrl}]+") String reason) { }
    public record Charge(UUID id,String status,String providerId,BigDecimal amount,Instant expiresAt,Instant closedAt) { }
    public record Attempt(int number,Instant startedAt,Instant finishedAt,String result,String error) { }
    public record Refund(UUID id,UUID chargeId,String providerPaymentId,String status,String providerId,BigDecimal amount,String reason,
        Instant requestedAt,Instant completedAt,Instant nextAttemptAt,String error,List<Attempt> attempts) { }
    public record Page<T>(List<T> items,UUID nextCursor) { }
}
