package br.com.olius.olius_core_api.service;

import br.com.olius.olius_core_api.dto.CertificateContracts.*;
import br.com.olius.olius_core_api.entity.enums.UserType;
import br.com.olius.olius_core_api.exception.ResourceNotFoundException;
import br.com.olius.olius_core_api.repository.CertificateReadRepository;
import br.com.olius.olius_core_api.repository.CollectionRequestRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
@Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
public class CertificateReadService {
    private final CertificateReadRepository certificates;
    private final CollectionRequestRepository actors;
    public CertificateReadService(CertificateReadRepository certificates,CollectionRequestRepository actors) {
        this.certificates=certificates;this.actors=actors;
    }
    public List<Level> levels(UUID actor,boolean admin) { requireActor(actor,admin);return certificates.levels(); }
    public Page list(UUID actor,boolean admin,UUID establishment,Query query) {
        requireActor(actor,admin);return certificates.list(admin ? establishment : actor,query);
    }
    public View detail(UUID id,UUID actor,boolean admin) {
        requireActor(actor,admin);return certificates.find(id,admin ? null : actor).orElseThrow(ResourceNotFoundException::new);
    }
    public Progress progress(UUID actor,boolean admin,UUID establishment) {
        requireActor(actor,admin);UUID target=admin ? establishment : actor;
        if(!certificates.establishmentExists(target)) throw new ResourceNotFoundException();
        return certificates.progress(target);
    }
    private void requireActor(UUID actor,boolean admin) {
        if(!actors.activeActor(actor,admin ? UserType.ADMIN : UserType.ESTABLISHMENT,false))
            throw new AccessDeniedException("Operação não autorizada");
    }
}
