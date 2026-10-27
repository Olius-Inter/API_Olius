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
    /** Consulta os candidatos sem alterar o estado financeiro. */
    @Transactional(readOnly=true) public List<UUID> due() { return refunds.due(); }
    /** Reivindica a tentativa em uma transação curta com autoria SYSTEM. */
    @Transactional public List<Claim> claim(UUID id) {
        audit.system("Execução de reembolso integral autorizado pelo banco");return refunds.claim(id);
    }
    /** Grava o resultado apenas da tentativa atual, com auditoria do sistema. */
    @Transactional public void complete(Claim claim,RefundGateway.Result result) {
        if(!refunds.lockCurrent(claim)) return;
        audit.system("Resultado de reembolso verificado na Stripe");refunds.save(claim,result);
    }
    /** Registra a falha e sua política de repetição somente se a tentativa ainda for atual. */
    @Transactional public void fail(Claim claim,RefundGateway.FailureKind kind) {
        if(!refunds.lockCurrent(claim)) return;
        audit.system("Falha técnica na execução de reembolso");refunds.fail(claim,kind);
    }
}
