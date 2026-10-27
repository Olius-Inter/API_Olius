package br.com.olius.olius_core_api.dto;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.util.UUID;

public record CheckoutResponse(UUID chargeId, UUID planId, BigDecimal amount,
                               String currency, Instant expiresAt, URI paymentUrl) { }
