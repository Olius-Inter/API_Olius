package br.com.olius.olius_core_api.service;

import br.com.olius.olius_core_api.integration.payment.PaymentVerificationGateway.VerifiedPayment;
import br.com.olius.olius_core_api.repository.PaymentEventRepository;
import br.com.olius.olius_core_api.repository.PaymentEventRepository.Claim;
import br.com.olius.olius_core_api.repository.VerifiedPaymentRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PaymentEventTransactions {
    private final PaymentEventRepository events;
    private final VerifiedPaymentRepository payments;
    private final AuditContext audit;
    public PaymentEventTransactions(PaymentEventRepository events,VerifiedPaymentRepository payments,AuditContext audit) {
        this.events=events;this.payments=payments;this.audit=audit;
    }
    @Transactional(readOnly=true)
    public List<UUID> due() { return events.due(); }
    @Transactional
    public List<Claim> claim(UUID id) { return events.claim(id); }
    @Transactional
    public void complete(Claim claim,VerifiedPayment verified) {
        if(!events.lockCurrent(claim)) return;
        if(!claim.paymentId().equals(verified.paymentId()))
            throw new br.com.olius.olius_core_api.exception.PaymentProviderUnavailableException();
        audit.system("Confirmação financeira Stripe verificada");
        UUID id=payments.record(verified);
        payments.apply(id);
        events.processed(claim,id);
    }
    @Transactional
    public void fail(Claim claim) { events.failed(claim); }
}
