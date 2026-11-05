package br.com.olius.olius_core_api.service;

import br.com.olius.olius_core_api.dto.AddressReplacement;
import br.com.olius.olius_core_api.dto.IdentityResponses.AddressView;
import br.com.olius.olius_core_api.entity.Address.OwnerKind;
import br.com.olius.olius_core_api.entity.enums.UserType;
import br.com.olius.olius_core_api.exception.BusinessRuleException;
import br.com.olius.olius_core_api.exception.ResourceNotFoundException;
import br.com.olius.olius_core_api.repository.AddressMaintenanceRepository;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EstablishmentAddressService {
    private final AddressMaintenanceRepository repository;
    private final AuditContext audit;
    public EstablishmentAddressService(AddressMaintenanceRepository repository,AuditContext audit) {
        this.repository=repository; this.audit=audit;
    }
    /** Não exige plano; serializa com novos pedidos e bloqueia mudança enquanto houver visita em andamento. */
    @Transactional
    public AddressView replace(UUID actor,AddressReplacement request) {
        if(!repository.activeActor(actor,UserType.ESTABLISHMENT)) throw new AccessDeniedException("Conta indisponível");
        UUID address=repository.lockEstablishment(actor).orElseThrow(ResourceNotFoundException::new);
        AddressView previous=repository.find(address,OwnerKind.ESTABLISHMENT);
        if(AddressMaintenanceRepository.same(previous,request.address())) return previous;
        if(repository.hasUnfinishedRequests(actor))
            throw new BusinessRuleException(BusinessRuleException.Reason.STATE_CONFLICT);
        audit.user(actor,"Atualização do próprio endereço comercial");
        repository.replace(address,OwnerKind.ESTABLISHMENT,request.address());
        return repository.find(address,OwnerKind.ESTABLISHMENT);
    }
}
