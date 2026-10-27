package br.com.olius.olius_core_api.service;

import br.com.olius.olius_core_api.exception.*;
import br.com.olius.olius_core_api.integration.payment.CheckoutGateway.CheckoutSession;
import br.com.olius.olius_core_api.repository.BillingCheckoutRepository;
import br.com.olius.olius_core_api.repository.BillingCheckoutRepository.Charge;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Associação comum aos fluxos de cobrança; não concede benefícios. */
@Service
public class CheckoutAssociationTransactions {
    private final BillingCheckoutRepository bills;
    private final AuditContext audit;
    public CheckoutAssociationTransactions(BillingCheckoutRepository bills,AuditContext audit) {
        this.bills=bills;this.audit=audit;
    }
    @Transactional
    public void associate(UUID owner,Charge expected,CheckoutSession external) {
        var charge=bills.lockCharge(expected.id()).stream().findFirst().orElseThrow(ResourceNotFoundException::new);
        if(!charge.ownerId().equals(owner) || !bills.activeOwner(owner)) throw new AccessDeniedException("Titular indisponível");
        requireOpen(charge);
        if(charge.amount().compareTo(expected.amount())!=0 || !charge.key().equals(expected.key())
                || !charge.expiresAt().equals(external.expiresAt())) throw new ResourceConflictException();
        if(charge.providerId()!=null && !charge.providerId().equals(external.providerId())) throw new ResourceConflictException();
        if(charge.providerId()==null) {
            audit.user(owner,"Associação do Checkout Stripe à cobrança");
            bills.associate(charge.id(),external.providerId());
        }
    }
    private void requireOpen(Charge charge) {
        if(!"OPEN".equals(charge.status()) || !charge.expiresAt().isAfter(bills.now()))
            throw new BusinessRuleException(BusinessRuleException.Reason.STATE_CONFLICT);
    }
}
