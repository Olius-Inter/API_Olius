package br.com.olius.olius_core_api.service;

import br.com.olius.olius_core_api.dto.AdminInvitationResponses.*;
import br.com.olius.olius_core_api.repository.AdminInvitationRepository;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** Termina o commit antes de o orquestrador entregar o segredo ao serviço de mensagem. */
@Service
public class AdminInvitationTransactions {
    private final AdminRegistry registry;
    private final AdminInvitationRepository invitations;

    public AdminInvitationTransactions(AdminRegistry registry,AdminInvitationRepository invitations) {
        this.registry=registry; this.invitations=invitations;
    }

    @Transactional(propagation=Propagation.REQUIRES_NEW)
    @PreAuthorize("hasRole('ADMIN')")
    public Invitation issue(UUID actor,String name,String email,byte[] hash) {
        registry.lock(); registry.requireActive(actor);
        return invitations.get(invitations.issue(actor,name,email,hash));
    }

    @Transactional
    public UUID accept(byte[] hash,String passwordHash) {
        registry.lock();
        return invitations.accept(hash,passwordHash);
    }

    @Transactional
    @PreAuthorize("hasRole('ADMIN')")
    public void revoke(UUID actor,UUID id) {
        registry.lock(); registry.requireActive(actor);
        invitations.revoke(actor,id);
    }

    @Transactional(readOnly=true)
    @PreAuthorize("hasRole('ADMIN')")
    public Invitation get(UUID actor,UUID id) {
        registry.requireActive(actor);
        try { return invitations.get(id); }
        catch(org.springframework.dao.EmptyResultDataAccessException missing) {
            throw new br.com.olius.olius_core_api.exception.ResourceNotFoundException();
        }
    }

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    @PreAuthorize("hasRole('ADMIN')")
    public Page list(UUID actor,State state,int page,int size) {
        registry.requireActive(actor);
        return invitations.list(state,page,size);
    }
}
