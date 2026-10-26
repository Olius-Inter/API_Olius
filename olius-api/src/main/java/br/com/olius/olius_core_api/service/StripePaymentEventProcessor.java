package br.com.olius.olius_core_api.service;

import br.com.olius.olius_core_api.integration.payment.PaymentVerificationGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class StripePaymentEventProcessor {
    private static final Logger LOG=LoggerFactory.getLogger(StripePaymentEventProcessor.class);
    private final PaymentEventTransactions transactions;
    private final ObjectProvider<PaymentVerificationGateway> gateways;
    public StripePaymentEventProcessor(PaymentEventTransactions transactions,ObjectProvider<PaymentVerificationGateway> gateways) {
        this.transactions=transactions;this.gateways=gateways;
    }
    @Transactional(propagation=Propagation.NEVER)
    public void processDue() {
        var gateway=gateways.getIfAvailable();
        if(gateway==null) return;
        for(var id:transactions.due()) {
            var claims=transactions.claim(id);
            if(claims.isEmpty()) continue;
            var claim=claims.getFirst();
            try {
                var verified=gateway.verify(claim.eventId(),claim.paymentId());
                transactions.complete(claim,verified);
            } catch(RuntimeException ex) {
                transactions.fail(claim);
                LOG.warn("Falha no processamento financeiro eventId={} attempt={} exceptionType={}",
                        claim.id(),claim.attempt(),ex.getClass().getName());
            }
        }
    }
}
