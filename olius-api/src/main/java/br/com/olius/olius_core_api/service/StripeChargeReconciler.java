package br.com.olius.olius_core_api.service;

import br.com.olius.olius_core_api.integration.payment.ChargeReconciliationGateway;
import java.time.Instant;
import java.time.Duration;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class StripeChargeReconciler {
    private static final Logger LOG=LoggerFactory.getLogger(StripeChargeReconciler.class);
    private final ChargeReconciliationTransactions transactions;
    private final ObjectProvider<ChargeReconciliationGateway> gateways;
    private UUID chargeCursor;
    private String eventCursor;
    private Instant scanUntil;
    public StripeChargeReconciler(ChargeReconciliationTransactions transactions,ObjectProvider<ChargeReconciliationGateway> gateways) {
        this.transactions=transactions;this.gateways=gateways;
    }
    /** Recupera uma página de eventos e de cobranças; falhas de leitura não presumem pagamento ou ausência dele. */
    @Transactional(propagation=Propagation.NEVER)
    public synchronized void processDue() {
        var gateway=gateways.getIfAvailable();if(gateway==null) return;
        if(scanUntil==null) scanUntil=Instant.now();
        try {
            // Cada execução avança uma página; reinício pode reler eventos, cuja gravação é idempotente.
            var page=gateway.events(scanUntil.minus(Duration.ofDays(30)),scanUntil,eventCursor);
            transactions.receive(page.notices());eventCursor=page.nextCursor();if(eventCursor==null) scanUntil=null;
        } catch(RuntimeException failure) {
            LOG.warn("Falha na recuperação de eventos Stripe exceptionType={}",failure.getClass().getName());
        }
        var ids=transactions.due(chargeCursor);
        for(UUID id:ids) {
            try {
                var claims=transactions.prepare(id);if(claims.isEmpty()) continue;
                var observed=gateway.reconcile(claims.getFirst());transactions.finish(claims.getFirst(),observed);
                if(observed.state()==ChargeReconciliationGateway.State.UNCERTAIN)
                    LOG.warn("Cobrança permanece pendente de verificação chargeId={}",id);
            } catch(RuntimeException failure) {
                LOG.warn("Falha na reconciliação da cobrança chargeId={} exceptionType={}",id,failure.getClass().getName());
            } finally { chargeCursor=id; }
        }
        if(ids.size()<10) chargeCursor=null;
    }
}
