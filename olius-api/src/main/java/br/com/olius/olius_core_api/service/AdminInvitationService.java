package br.com.olius.olius_core_api.service;

import br.com.olius.olius_core_api.dto.AdminInvitationRequests.*;
import br.com.olius.olius_core_api.dto.AdminInvitationResponses.Invitation;
import br.com.olius.olius_core_api.exception.AdminInvitationDeliveryException;
import br.com.olius.olius_core_api.security.SecretTokens;
import java.util.Locale;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

@Service
public class AdminInvitationService {
    private final AdminInvitationTransactions transactions;
    private final SecretTokens secrets;
    private final PasswordEncoder passwords;
    private final ObjectProvider<AdminInvitationDelivery> deliveries;

    public AdminInvitationService(AdminInvitationTransactions transactions,SecretTokens secrets,
            PasswordEncoder passwords,ObjectProvider<AdminInvitationDelivery> deliveries) {
        this.transactions=transactions; this.secrets=secrets; this.passwords=passwords; this.deliveries=deliveries;
    }

    @PreAuthorize("hasRole('ADMIN')")
    @Transactional(propagation=Propagation.NEVER)
    public Invitation issue(UUID actor,Issue request) {
        var delivery=deliveries.getIfAvailable();
        if(delivery==null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
        String token=secrets.generate();
        var invitation=transactions.issue(actor,request.name().strip(),
                request.email().strip().toLowerCase(Locale.ROOT),secrets.hash(token));
        // REQUIRES_NEW já confirmou. Falha externa não desfaz o convite nem gera reenvio oculto.
        try { delivery.send(new AdminInvitationDelivery.Message(invitation.name(),invitation.email(),token)); }
        catch(RuntimeException failure) { throw new AdminInvitationDeliveryException(failure); }
        return invitation;
    }

    public UUID accept(Accept request) {
        return transactions.accept(secrets.hash(request.token()),passwords.encode(request.password()));
    }
}
