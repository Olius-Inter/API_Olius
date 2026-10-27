package br.com.olius.olius_core_api.service;

import br.com.olius.olius_core_api.dto.CheckoutResponse;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InitialCheckoutService {
    private final InitialCheckoutTransactions transactions;
    private final CheckoutSessionService sessions;
    public InitialCheckoutService(InitialCheckoutTransactions transactions,CheckoutSessionService sessions) {
        this.transactions=transactions;this.sessions=sessions;
    }
    @Transactional(propagation=Propagation.NEVER)
    public CheckoutResponse create(UUID owner,UUID planId,UUID idempotencyKey) {
        var gateway=sessions.requireGateway();
        var stored=transactions.prepare(owner,planId,idempotencyKey);
        return sessions.link(owner,stored,gateway);
    }
}
