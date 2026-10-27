package br.com.olius.olius_core_api.service;

import br.com.olius.olius_core_api.config.BillingProperties;
import br.com.olius.olius_core_api.exception.*;
import br.com.olius.olius_core_api.repository.BillingCheckoutRepository;
import br.com.olius.olius_core_api.repository.BillingCheckoutRepository.Charge;
import java.time.Duration;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RenewalCheckoutTransactions {
    private final BillingCheckoutRepository bills;
    private final AuditContext audit;
    private final BillingProperties properties;
    public RenewalCheckoutTransactions(BillingCheckoutRepository bills,AuditContext audit,BillingProperties properties) {
        this.bills=bills;this.audit=audit;this.properties=properties;
    }
    @Transactional
    public Charge prepare(UUID owner,UUID planId,UUID key) {
        if(!bills.lockActiveEstablishment(owner)) throw new AccessDeniedException("Renovação restrita ao titular ativo");
        var previous=bills.byKey(key);
        if(!previous.isEmpty()) {
            var charge=previous.getFirst();
            if(!charge.ownerId().equals(owner) || !charge.planId().equals(planId)
                    || !"RENEWAL".equals(charge.purpose()) || !"STRIPE".equals(charge.provider()))
                throw new ResourceConflictException();
            if(!"OPEN".equals(charge.status()) || !charge.expiresAt().isAfter(bills.now())) throw conflict();
            return charge;
        }
        if(bills.hasOpenPeriodCharge(owner)) throw conflict();
        var cycle=bills.latestCycle(owner).stream().findFirst().orElseThrow(this::conflict);
        if(cycle.startsAt().isAfter(bills.currentInstant()) || bills.hasAnotherActiveSubscription(owner,cycle.subscriptionId()))
            throw conflict();
        var quote=bills.activeQuote(planId).stream().findFirst().orElseThrow(ResourceNotFoundException::new);
        Duration ttl=properties.getRenewalCheckoutTtl();
        if(ttl==null || ttl.compareTo(Duration.ofMinutes(30))<=0 || ttl.compareTo(Duration.ofHours(24))>0)
            throw new IllegalStateException("Configure a validade da renovação acima de 30 minutos e até 24 horas");
        audit.user(owner,"Solicitação de renovação manual");
        UUID order=bills.renewalOrder(owner,cycle);
        return bills.insertPeriod(owner,order,key,quote,bills.now().plus(ttl),"RENEWAL");
    }
    private BusinessRuleException conflict() { return new BusinessRuleException(BusinessRuleException.Reason.STATE_CONFLICT); }
}
