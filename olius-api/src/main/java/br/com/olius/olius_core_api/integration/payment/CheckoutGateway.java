package br.com.olius.olius_core_api.integration.payment;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.util.UUID;

/** Contrato interno: os valores devem vir da cobrança persistida, nunca do formulário. */
public interface CheckoutGateway {
    CheckoutSession create(CheckoutCharge charge);
    CheckoutSession retrieve(CheckoutCharge charge, String providerId);

    record CheckoutCharge(UUID chargeId, UUID idempotencyKey, String planName,
                          BigDecimal amount, Instant expiresAt) { }
    record CheckoutSession(String providerId, URI paymentUrl, Instant expiresAt) { }
}
