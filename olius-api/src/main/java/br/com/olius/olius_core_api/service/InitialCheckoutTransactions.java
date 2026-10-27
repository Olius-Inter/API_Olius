package br.com.olius.olius_core_api.service;

import br.com.olius.olius_core_api.config.BillingProperties;
import br.com.olius.olius_core_api.exception.BusinessRuleException;
import br.com.olius.olius_core_api.exception.ResourceConflictException;
import br.com.olius.olius_core_api.exception.ResourceNotFoundException;
import br.com.olius.olius_core_api.repository.BillingCheckoutRepository;
import br.com.olius.olius_core_api.repository.BillingCheckoutRepository.Charge;
import java.time.Duration;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Transações curtas, separadas da chamada externa pelo orquestrador. */
@Service
public class InitialCheckoutTransactions {
    private final BillingCheckoutRepository bills;
    private final AuditContext audit;
    private final BillingProperties properties;
    public InitialCheckoutTransactions(BillingCheckoutRepository bills,AuditContext audit,BillingProperties properties) {
        this.bills=bills; this.audit=audit; this.properties=properties;
    }
    @Transactional
    public Charge prepare(UUID owner,UUID planId,UUID key) {
        if(!bills.lockActiveEstablishment(owner)) throw new AccessDeniedException("Contratação restrita a estabelecimento ativo");
        var previous=bills.byKey(key);
        if(!previous.isEmpty()) {
            var charge=previous.getFirst();
            if(!charge.ownerId().equals(owner) || !charge.planId().equals(planId)
                    || !"INITIAL".equals(charge.purpose()) || !"STRIPE".equals(charge.provider()))
                throw new ResourceConflictException();
            requireOpen(charge);
            return charge;
        }
        if(bills.hasPaidHistory(owner) || bills.hasOpenPeriodCharge(owner)) throw conflict();
        var quotes=bills.activeQuote(planId);
        if(quotes.isEmpty()) throw new ResourceNotFoundException();
        Duration ttl=properties.getInitialCheckoutTtl();
        if(ttl==null || ttl.compareTo(Duration.ofMinutes(30))<=0 || ttl.compareTo(Duration.ofHours(24))>0)
            throw new IllegalStateException("Validade do Checkout deve superar 30 minutos e não ultrapassar 24 horas");
        audit.user(owner,"Solicitação de contratação inicial");
        UUID subscription=bills.pendingSubscription(owner);
        UUID order=bills.initialOrder(owner,subscription);
        return bills.insert(owner,order,key,quotes.getFirst(),bills.now().plus(ttl));
    }
    private void requireOpen(Charge charge) {
        if(!"OPEN".equals(charge.status()) || !charge.expiresAt().isAfter(bills.now())) throw conflict();
    }
    private BusinessRuleException conflict() { return new BusinessRuleException(BusinessRuleException.Reason.STATE_CONFLICT); }
}
