package br.com.olius.olius_core_api.service;

import br.com.olius.olius_core_api.dto.IdentityRequests.AddressInput;
import br.com.olius.olius_core_api.dto.IdentityResponses.AddressView;
import br.com.olius.olius_core_api.dto.PevRequests.Create;
import br.com.olius.olius_core_api.dto.PevResponses.*;
import br.com.olius.olius_core_api.entity.Address;
import br.com.olius.olius_core_api.entity.Pev;
import br.com.olius.olius_core_api.entity.enums.PevStatus;
import br.com.olius.olius_core_api.entity.enums.UserStatus;
import br.com.olius.olius_core_api.entity.enums.UserType;
import br.com.olius.olius_core_api.exception.ResourceNotFoundException;
import br.com.olius.olius_core_api.repository.PevRepository;
import br.com.olius.olius_core_api.repository.UserRepository;
import jakarta.persistence.EntityManager;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PevService {
    private final EntityManager em;
    private final PevRepository pevs;
    private final UserRepository users;
    private final AuditContext audit;

    public PevService(EntityManager em, PevRepository pevs, UserRepository users, AuditContext audit) {
        this.em=em; this.pevs=pevs; this.users=users; this.audit=audit;
    }

    @Transactional
    public OwnPev request(Create request, UUID actor, UserType type) {
        requireOwner(actor, type);
        audit.user(actor, "Solicitação de PEV pelo responsável");
        AddressInput input=request.address();
        UUID addressId=UUID.randomUUID();
        em.persist(Address.forPev(addressId,
            new Address.PostalDetails(input.state(),input.city(),input.neighborhood(),input.street(),
                input.number(),input.cep(),input.complement()),
            new Address.Coordinates(input.latitude(),input.longitude())));
        em.flush();

        Pev pev=new Pev(UUID.randomUUID(),type==UserType.CITIZENS ? actor : null,
            type==UserType.ESTABLISHMENT ? actor : null,addressId);
        em.persist(pev);
        // Force unique/FK failures inside this transaction so the address rolls back too.
        em.flush();
        em.refresh(pev);
        return own(pev);
    }

    @Transactional(readOnly=true)
    public OwnPev own(UUID actor, UserType type) {
        requireOwner(actor,type);
        Pev pev=(type==UserType.CITIZENS ? pevs.findByCitizenId(actor) : pevs.findByEstablishmentId(actor))
            .orElseThrow(ResourceNotFoundException::new);
        return own(pev);
    }

    @Transactional(readOnly=true)
    public PublicPage approved(int page, int size) {
        var result=pevs.findByStatus(PevStatus.APPROVED,
            PageRequest.of(page,size,Sort.by(Sort.Direction.ASC,"createdAt","id")));
        return new PublicPage(result.stream().map(pev -> new PublicPev(pev.getId(),address(pev.getAddress())))
            .toList(),page,size,result.getTotalElements());
    }

    private void requireOwner(UUID actor, UserType type) {
        if(type!=UserType.CITIZENS && type!=UserType.ESTABLISHMENT)
            throw new AccessDeniedException("Perfil sem vínculo de PEV");
        var user=users.findById(actor).orElseThrow(() -> new AccessDeniedException("Conta indisponível"));
        if(user.getStatus()!=UserStatus.ACTIVE || user.getUserType()!=type)
            throw new AccessDeniedException("Conta indisponível");
    }

    private OwnPev own(Pev pev) {
        return new OwnPev(pev.getId(),pev.getStatus(),pev.getCreatedAt(),pev.getApprovedAt(),
            address(pev.getAddress()));
    }

    private AddressView address(Address a) {
        return new AddressView(a.getId(),a.getState(),a.getCity(),a.getNeighborhood(),a.getStreet(),
            a.getNumber(),a.getCep(),a.getComplement(),a.getLatitude(),a.getLongitude());
    }
}
