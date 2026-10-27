package br.com.olius.olius_core_api.integration.payment;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public interface PaymentVerificationGateway {
    VerifiedPayment verify(String eventId,String paymentId);
    record VerifiedPayment(String paymentId,UUID chargeId,BigDecimal amount,String currency,Instant paidAt) { }
}
