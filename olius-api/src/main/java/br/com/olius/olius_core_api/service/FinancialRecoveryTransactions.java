package br.com.olius.olius_core_api.service;

import br.com.olius.olius_core_api.dto.FinancialRecoveryContracts.*;
import br.com.olius.olius_core_api.exception.*;
import br.com.olius.olius_core_api.integration.payment.*;
import br.com.olius.olius_core_api.repository.*;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FinancialRecoveryTransactions {
    private static final String FIELD_CANCELLATION_PENDING="CANCELLATION_PENDING";
    private final FinancialRecoveryRepository operations;
    private final ChargeReconciliationRepository charges;
    private final BillingCheckoutRepository bills;
    private final RefundRepository refunds;
    private final AuditContext audit;
    public FinancialRecoveryTransactions(FinancialRecoveryRepository operations,ChargeReconciliationRepository charges,
            BillingCheckoutRepository bills,RefundRepository refunds,AuditContext audit) {
        this.operations=operations;this.charges=charges;this.bills=bills;this.refunds=refunds;this.audit=audit;
    }
    private void admin(UUID actor) { if(!operations.activeAdmin(actor)) throw new AccessDeniedException("Administrador inativo"); }
    @Transactional(readOnly=true) public Page<Charge> charges(UUID actor,UUID after,int size) { admin(actor);return operations.charges(after,size); }
    @Transactional(readOnly=true) public Page<Refund> refunds(UUID actor,UUID after,int size) { admin(actor);return operations.refunds(after,size); }
    @Transactional(readOnly=true) public Charge charge(UUID actor,UUID id) { admin(actor);return operations.charge(id); }
    @Transactional(readOnly=true) public Refund refund(UUID actor,UUID id) { admin(actor);return operations.refund(id); }
    /** Revalida o administrador e prepara a cobrança; prazo vencido não significa expiração confirmada. */
    @Transactional public ChargeReconciliationGateway.Request prepareCharge(UUID actor,UUID id,String reason) {
        admin(actor);var rows=bills.lockCharge(id);if(rows.isEmpty()) { throw new ResourceNotFoundException(); }
        var c=rows.getFirst();
        if(!"STRIPE".equals(c.provider()) || !java.util.List.of("OPEN",FIELD_CANCELLATION_PENDING).contains(c.status())) throw new ResourceConflictException();
        audit.user(actor,reason);
        if("OPEN".equals(c.status()) && !c.expiresAt().isAfter(bills.currentInstant())) charges.state(id,FIELD_CANCELLATION_PENDING);
        return charges.snapshot(id);
    }
    /** Relê a cobrança sob bloqueio e aplica somente uma expiração externa compatível, preservando pagamentos concorrentes. */
    @Transactional public void finishCharge(UUID actor,String reason,ChargeReconciliationGateway.Request expected,ChargeReconciliationGateway.Snapshot observed) {
        admin(actor);var rows=bills.lockCharge(expected.chargeId());if(rows.isEmpty()) { throw new ResourceNotFoundException(); }
        var c=rows.getFirst();
        if(!"STRIPE".equals(c.provider()) || !c.key().equals(expected.key()) || c.amount().compareTo(expected.amount())!=0 || !c.expiresAt().equals(expected.expiresAt())
                || observed==null || observed.state()==null || (c.providerId()!=null && !c.providerId().equals(observed.providerId()))
                || (observed.state()==ChargeReconciliationGateway.State.EXPIRED && observed.providerId()==null)) throw new ResourceConflictException();
        audit.user(actor,reason);
        if(observed.providerId()!=null && c.providerId()==null) bills.associate(c.id(),observed.providerId());
        if(observed.state()==ChargeReconciliationGateway.State.EXPIRED && java.util.List.of("OPEN",FIELD_CANCELLATION_PENDING).contains(c.status())) charges.state(c.id(),"EXPIRED");
    }
    /** Reivindica uma consulta manual e conserva a condição anterior para recuperação após falha de GET. */
    @Transactional public RefundRepository.Claim prepareRefund(UUID actor,UUID id,String providerId,String reason) {
        admin(actor);audit.user(actor,reason);return operations.claimRefund(id,providerId);
    }
    /** Grava resultado verificado somente se a tentativa manual continuar sendo a atual. */
    @Transactional public void finishRefund(UUID actor,String reason,RefundRepository.Claim claim,RefundGateway.Result result) {
        admin(actor);if(!refunds.lockCurrent(claim)) throw new ResourceConflictException();
        audit.user(actor,reason);refunds.save(claim,result);
    }
    /** Restaura a condição anterior após falha de consulta, sem sobrescrever uma tentativa mais recente. */
    @Transactional public void failRefund(UUID actor,String reason,RefundRepository.Claim claim) {
        // Fecha a tentativa mesmo se a conta perder acesso durante o HTTP; a autoria da solicitação é preservada.
        if(!refunds.lockCurrent(claim)) { return; }
        audit.user(actor,reason);
        refunds.restoreAfterLookupFailure(claim);
    }
}
