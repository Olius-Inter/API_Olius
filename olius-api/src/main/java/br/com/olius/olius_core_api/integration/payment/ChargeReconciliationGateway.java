package br.com.olius.olius_core_api.integration.payment;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface ChargeReconciliationGateway {
    record Request(UUID chargeId,UUID key,BigDecimal amount,Instant createdAt,Instant expiresAt,String providerId) { }
    enum State { OPEN, EXPIRED, PAID, UNCERTAIN }
    record Snapshot(String providerId,State state) { }
    record Notice(String eventId,String paymentId,UUID chargeId) { }
    record Page(List<Notice> notices,String nextCursor) { }
    Snapshot reconcile(Request request);
    Page events(Instant from,Instant until,String after);
}
