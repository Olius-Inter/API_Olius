package br.com.olius.olius_core_api.integration.payment;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Devolve apenas pedidos integrais já autorizados e persistidos pelo banco. */
public interface RefundGateway {
    record Request(UUID refundId, UUID idempotencyKey, UUID chargeId, String paymentId,
                   BigDecimal amount, Instant firstAttemptAt) { }
    enum Status { SUCCEEDED, PENDING, REQUIRES_ACTION, FAILED, CANCELED }
    record Result(String providerId, Status status, String requestId) { }
    enum FailureKind { TEMPORARY, REVIEW_REQUIRED, RECONCILIATION_REQUIRED }
    final class Failure extends RuntimeException {
        private final FailureKind kind;
        public Failure(FailureKind kind) { super("REFUND_"+kind.name()); this.kind=kind; }
        public FailureKind kind() { return kind; }
    }
    Result create(Request request);
    Result retrieve(Request request, String providerId);
}
