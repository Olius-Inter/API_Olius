package br.com.olius.olius_core_api.service;

import br.com.olius.olius_core_api.integration.payment.RefundFailure;
import br.com.olius.olius_core_api.integration.payment.RefundGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class StripeRefundProcessor {
    private static final Logger LOG=LoggerFactory.getLogger(StripeRefundProcessor.class);
    private final RefundTransactions transactions;
    private final ObjectProvider<RefundGateway> gateways;
    public StripeRefundProcessor(RefundTransactions transactions,ObjectProvider<RefundGateway> gateways) {
        this.transactions=transactions;this.gateways=gateways;
    }
    /** Processa um lote fora de transação SQL; ID conhecido sempre é consultado em vez de recriado. */
    @Transactional(propagation=Propagation.NEVER)
    public void processDue() {
        var gateway=gateways.getIfAvailable();if(gateway==null) return;
        for(var id:transactions.due()) {
            var claims=transactions.claim(id);if(claims.isEmpty()) continue;
            var claim=claims.getFirst();
            try {
                var result=claim.providerId()==null ? gateway.create(claim.request()) : gateway.retrieve(claim.request(),claim.providerId());
                transactions.complete(claim,result);
            } catch(RefundFailure failure) {
                transactions.fail(claim,failure.kind());
                LOG.warn("Reembolso pendente de recuperação refundId={} kind={}",id,failure.kind());
            } catch(RuntimeException failure) {
                transactions.fail(claim,RefundGateway.FailureKind.TEMPORARY);
                LOG.warn("Falha técnica no reembolso refundId={} exceptionType={}",id,failure.getClass().getName());
            }
        }
    }
}
