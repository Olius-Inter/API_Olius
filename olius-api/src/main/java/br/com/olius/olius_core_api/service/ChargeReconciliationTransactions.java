package br.com.olius.olius_core_api.service;

import br.com.olius.olius_core_api.integration.payment.ChargeReconciliationGateway.*;
import br.com.olius.olius_core_api.repository.*;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ChargeReconciliationTransactions {
    private static final String FIELD_CANCELLATION_PENDING="CANCELLATION_PENDING";
    private final BillingCheckoutRepository bills;
    private final ChargeReconciliationRepository reconciliation;
    private final PaymentEventRepository events;
    private final AuditContext audit;
    public ChargeReconciliationTransactions(BillingCheckoutRepository bills,ChargeReconciliationRepository reconciliation,PaymentEventRepository events,AuditContext audit) {
        this.bills=bills;this.reconciliation=reconciliation;this.events=events;this.audit=audit;
    }
    @Transactional(readOnly=true) public List<UUID> due(UUID after) { return reconciliation.due(after); }
    /** Bloqueia a cobrança e prepara uma consulta externa; vencimento local mantém a vaga reservada. */
    @Transactional public List<Request> prepare(UUID id) {
        var current=bills.lockCharge(id);
        if(current.isEmpty()) return List.of();
        var charge=current.getFirst();
        if(!"STRIPE".equals(charge.provider()) || !List.of("OPEN",FIELD_CANCELLATION_PENDING).contains(charge.status())) return List.of();
        audit.system(charge.providerId()==null ? "Cobrança vencida sem ID externo; localizar no provedor antes de encerrar" : "Reconciliação externa da cobrança vencida");
        if("OPEN".equals(charge.status()) && !charge.expiresAt().isAfter(bills.currentInstant())) reconciliation.state(id,FIELD_CANCELLATION_PENDING);
        return List.of(reconciliation.snapshot(id));
    }
    /** Aplica a observação externa compatível sem substituir identidade nem sobrescrever um pagamento já aplicado. */
    @Transactional public void finish(Request expected,Snapshot observed) {
        var current=bills.lockCharge(expected.chargeId());if(current.isEmpty()) return;
        var charge=current.getFirst();
        if(observed==null || observed.state()==null || !"STRIPE".equals(charge.provider()) || !charge.key().equals(expected.key())
                || charge.amount().compareTo(expected.amount())!=0 || !charge.expiresAt().equals(expected.expiresAt()))
            throw new IllegalStateException("Resultado incompatível de reconciliação");
        if(observed.providerId()!=null && (charge.providerId()!=null && !charge.providerId().equals(observed.providerId())))
            throw new IllegalStateException("Identidade externa incompatível");
        if(observed.state()==State.EXPIRED && observed.providerId()==null) throw new IllegalStateException("Expiração sem identidade externa");
        audit.system("Estado da cobrança consultado na Stripe");
        if(observed.providerId()!=null && charge.providerId()==null) bills.associate(charge.id(),observed.providerId());
        if(observed.state()==State.EXPIRED && List.of("OPEN",FIELD_CANCELLATION_PENDING).contains(charge.status()))
            reconciliation.state(charge.id(),"EXPIRED");
    }
    /** Aceita eventos apenas de cobranças Stripe conhecidas e reutiliza a fila idempotente de pagamentos. */
    @Transactional public void receive(List<Notice> notices) {
        for(var notice:notices) if(reconciliation.exists(notice.chargeId())) events.receive(notice.eventId(),notice.paymentId());
    }
}
