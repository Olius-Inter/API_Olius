package br.com.olius.olius_core_api.service;

import br.com.olius.olius_core_api.dto.UpgradeCheckoutResponse;
import br.com.olius.olius_core_api.dto.UpgradeCheckoutResponse.UpgradeDetails;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UpgradeCheckoutService {
    private final UpgradeCheckoutTransactions transactions;
    private final CheckoutSessionService sessions;
    public UpgradeCheckoutService(UpgradeCheckoutTransactions transactions,CheckoutSessionService sessions) {
        this.transactions=transactions;this.sessions=sessions;
    }
    @Transactional(propagation=Propagation.NEVER)
    public UpgradeCheckoutResponse create(UUID owner,UUID planId,UUID key) {
        var gateway=sessions.requireGateway();
        var charge=transactions.prepare(owner,planId,key);
        var terms=transactions.details(owner,charge.id());
        var checkout=sessions.link(owner,charge,gateway);
        var details=new UpgradeDetails(terms.cycleId(),terms.cycleEndsAt(),terms.fromPlanId(),terms.fromMonthlyPrice(),
                terms.targetMonthlyPrice(),terms.targetLiters(),terms.targetSlots(),terms.currentAvailableLiters(),
                terms.currentAvailableSlots(),terms.availableLitersAfterUpgrade(),terms.availableSlotsAfterUpgrade(),terms.calculatedAt());
        return new UpgradeCheckoutResponse(checkout.chargeId(),checkout.planId(),checkout.amount(),checkout.currency(),
                checkout.expiresAt(),checkout.paymentUrl(),details);
    }
}
