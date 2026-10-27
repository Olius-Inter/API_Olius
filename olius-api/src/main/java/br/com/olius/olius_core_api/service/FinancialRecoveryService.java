package br.com.olius.olius_core_api.service;

import br.com.olius.olius_core_api.integration.payment.RefundFailure;
import br.com.olius.olius_core_api.dto.FinancialRecoveryContracts.*;
import br.com.olius.olius_core_api.exception.PaymentProviderUnavailableException;
import br.com.olius.olius_core_api.integration.payment.*;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
@PreAuthorize("hasRole('ADMIN')")
@Transactional(propagation=Propagation.NEVER)
public class FinancialRecoveryService {
    private final FinancialRecoveryTransactions transactions;
    private final ObjectProvider<ChargeReconciliationGateway> charges;
    private final ObjectProvider<RefundGateway> refunds;
    public FinancialRecoveryService(FinancialRecoveryTransactions transactions,ObjectProvider<ChargeReconciliationGateway> charges,ObjectProvider<RefundGateway> refunds) {
        this.transactions=transactions;this.charges=charges;this.refunds=refunds;
    }
    public Page<Charge> charges(UUID actor,UUID after,int size) { return transactions.charges(actor,after,size); }
    public Page<Refund> refunds(UUID actor,UUID after,int size) { return transactions.refunds(actor,after,size); }
    public Charge charge(UUID actor,UUID id) { return transactions.charge(actor,id); }
    public Refund refund(UUID actor,UUID id) { return transactions.refund(actor,id); }
    /** Consulta o provedor sem transação aberta; a resposta informa o estado local, não uma confirmação manual de pagamento. */
    public Charge reconcileCharge(UUID actor,UUID id,String reason) {
        var gateway=charges.getIfAvailable();if(gateway==null) throw new PaymentProviderUnavailableException();
        var request=transactions.prepareCharge(actor,id,reason);
        var observed=gateway.reconcile(request);transactions.finishCharge(actor,reason,request,observed);
        return transactions.charge(actor,id);
    }
    /** Consulta exclusivamente o ID existente; erros de GET preservam a elegibilidade anterior e não emitem outro POST. */
    public Refund reconcileRefund(UUID actor,UUID id,String providerId,String reason) {
        var gateway=refunds.getIfAvailable();if(gateway==null) throw new PaymentProviderUnavailableException();
        var claim=transactions.prepareRefund(actor,id,providerId,reason);
        RefundGateway.Result result;
        try { result=gateway.retrieve(claim.request(),providerId); }
        catch(RuntimeException failure) {
            transactions.failRefund(actor,reason,claim);throw new PaymentProviderUnavailableException();
        }
        try { transactions.finishRefund(actor,reason,claim,result); }
        catch(RefundFailure failure) { transactions.failRefund(actor,reason,claim);throw new PaymentProviderUnavailableException(); }
        catch(RuntimeException failure) { transactions.failRefund(actor,reason,claim);throw failure; }
        return transactions.refund(actor,id);
    }
}
