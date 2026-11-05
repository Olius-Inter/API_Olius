package br.com.olius.olius_core_api.service;

import br.com.olius.olius_core_api.dto.ProfileUpdate;
import br.com.olius.olius_core_api.dto.IdentityResponses.Profile;
import br.com.olius.olius_core_api.entity.enums.UserType;
import br.com.olius.olius_core_api.repository.ProfileUpdateRepository;
import java.util.HashSet;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProfileUpdateService {
    private final ProfileUpdateRepository repository;
    private final ProfileService profiles;
    private final AuditContext audit;
    public ProfileUpdateService(ProfileUpdateRepository repository,ProfileService profiles,AuditContext audit) {
        this.repository=repository; this.profiles=profiles; this.audit=audit;
    }
    /** Serializa o conjunto de telefones por titular e confirma nome/telefones no mesmo commit. */
    @Transactional
    public Profile update(UUID actor,UserType role,ProfileUpdate request) {
        if(!repository.lockActiveUser(actor)) throw new AccessDeniedException("Conta indisponível");
        audit.user(actor,"Atualização do próprio nome e telefones");
        if(request.hasName()) repository.updateName(actor,request.getName().strip());
        if(request.hasTelephones()) replacePhones(actor,request);
        return profiles.get(actor,actor,role);
    }
    private void replacePhones(UUID actor,ProfileUpdate request) {
        var remaining=new HashSet<>(request.getTelephones());
        for(var current:repository.phones(actor)) {
            if(!remaining.remove(current.number())) repository.removePhone(actor,current.id());
        }
        for(String number:remaining) repository.addPhone(actor,number);
    }
}
