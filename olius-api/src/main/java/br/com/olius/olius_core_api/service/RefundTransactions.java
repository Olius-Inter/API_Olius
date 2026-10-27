package br.com.olius.olius_core_api.service;

import br.com.olius.olius_core_api.integration.payment.RefundGateway;
import br.com.olius.olius_core_api.repository.RefundRepository;
import br.com.olius.olius_core_api.repository.RefundRepository.Claim;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RefundTransactions {
    private final RefundRepository refunds;
    private final AuditContext audit;
    public RefundTransactions(RefundRepository refunds,AuditContext audit) { this.refunds=refunds;this.audit=audit; }
    @Transactional(readOnly=true) public List<UUID> due() { return refunds.due(); }
    @Transactional public List<Claim> claim(UUID id) {
        audit.system("Execução de reembolso integral autorizado pelo banco");return refunds.claim(id);
    }
    @Transactional public void complete(Claim claim,RefundGateway.Result result) {
        if(!refunds.lockCurrent(claim)) return;
        audit.system("Resultado de reembolso verificado na Stripe");refunds.save(claim,result);
    }
    @Transactional public void fail(Claim claim,RefundGateway.FailureKind kind) {
        if(!refunds.lockCurrent(claim)) return;
        audit.system("Falha técnica na execução de reembolso");refunds.fail(claim,kind);
    }
}
