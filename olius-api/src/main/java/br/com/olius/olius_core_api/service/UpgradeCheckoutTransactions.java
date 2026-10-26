package br.com.olius.olius_core_api.service;

import br.com.olius.olius_core_api.config.BillingProperties;
import br.com.olius.olius_core_api.exception.*;
import br.com.olius.olius_core_api.repository.BillingCheckoutRepository;
import br.com.olius.olius_core_api.repository.BillingCheckoutRepository.Charge;
import br.com.olius.olius_core_api.repository.BillingCheckoutRepository.UpgradeTerms;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UpgradeCheckoutTransactions {
    private static final Duration MINIMUM_WINDOW=Duration.ofMinutes(35);
    private final BillingCheckoutRepository bills;
    private final AuditContext audit;
    private final BillingProperties properties;
    public UpgradeCheckoutTransactions(BillingCheckoutRepository bills,AuditContext audit,BillingProperties properties) {
        this.bills=bills;this.audit=audit;this.properties=properties;
    }
    @Transactional
    public Charge prepare(UUID owner,UUID planId,UUID key) {
        if(!bills.lockActiveEstablishment(owner)) throw new AccessDeniedException("Upgrade restrito ao titular ativo");
        var existing=bills.byKey(key);
        if(!existing.isEmpty()) {
            var stored=existing.getFirst();
            if(!stored.ownerId().equals(owner) || !stored.planId().equals(planId)
                    || !"UPGRADE".equals(stored.purpose()) || !"STRIPE".equals(stored.provider()))
                throw new ResourceConflictException();
            if(!"OPEN".equals(stored.status()) || !stored.expiresAt().isAfter(bills.currentInstant())) throw conflict();
            return stored; // Não reaplica a janela mínima à recuperação de link já emitido.
        }
        var first=bills.currentUpgradeCycle(owner).stream().findFirst().orElseThrow(this::conflict);
        if(bills.hasOpenUpgrade(first.id())) throw conflict();
        // Uma confirmação concorrente pode ter atualizado o plano: reler depois de conferir a vaga.
        var current=bills.currentUpgradeCycle(owner).stream().findFirst().orElseThrow(this::conflict);
        if(!current.id().equals(first.id())) throw conflict();
        var target=bills.activeQuote(planId).stream().findFirst().orElseThrow(ResourceNotFoundException::new);
        if(target.id().equals(current.plan().id()) || target.price().compareTo(current.plan().price())<=0
                || target.liters().compareTo(current.plan().liters())<=0 || target.slots()<=current.plan().slots())
            throw conflict();
        Duration ttl=properties.getUpgradeCheckoutTtl();
        if(ttl==null || ttl.compareTo(MINIMUM_WINDOW)<=0 || ttl.compareTo(Duration.ofHours(1))>0)
            throw new IllegalStateException("Prazo de upgrade deve superar 35 minutos e não ultrapassar uma hora");
        var now=bills.currentInstant();
        var expires=now.plus(ttl).truncatedTo(ChronoUnit.SECONDS);
        var cycleEnd=current.endsAt().truncatedTo(ChronoUnit.SECONDS);
        if(cycleEnd.isBefore(expires)) expires=cycleEnd;
        if(!expires.isAfter(now.plus(MINIMUM_WINDOW))) throw conflict();
        audit.user(owner,"Solicitação de upgrade do ciclo vigente");
        UUID order=bills.upgradeOrder(owner,current,target.id());
        return bills.insertUpgrade(owner,order,key,current,target,expires);
    }
    @Transactional(readOnly=true)
    public UpgradeTerms details(UUID owner,UUID charge) { return bills.upgradeTerms(owner,charge); }
    private BusinessRuleException conflict() { return new BusinessRuleException(BusinessRuleException.Reason.STATE_CONFLICT); }
}
