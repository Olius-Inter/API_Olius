package br.com.olius.olius_core_api.service;

import br.com.olius.olius_core_api.dto.CollectionContracts.*;
import br.com.olius.olius_core_api.entity.enums.UserType;
import br.com.olius.olius_core_api.exception.ResourceNotFoundException;
import br.com.olius.olius_core_api.repository.CollectionReadRepository;
import br.com.olius.olius_core_api.repository.CollectionRequestRepository;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CollectionReadService {
    private final CollectionReadRepository collections;
    private final CollectionRequestRepository actors;
    public CollectionReadService(CollectionReadRepository collections,CollectionRequestRepository actors) { this.collections=collections;this.actors=actors; }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Page list(UUID actor,boolean administrative,UUID establishment,UUID driver,Query query) {
        requireActor(actor,administrative);return collections.list(administrative ? establishment : actor,driver,query);
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Detail detail(UUID id,UUID actor,boolean administrative) {
        requireActor(actor,administrative);return uncheckedDetail(id,administrative ? null : actor);
    }
    // Usado somente pelos Services deste pacote após autorização e dentro da transação da operação.
    Detail uncheckedDetail(UUID id,UUID owner) {
        var value=collections.find(id,owner).orElseThrow(ResourceNotFoundException::new);
        var calculation=collections.calculation(id).orElseThrow(()->new IllegalStateException("Coleta sem cálculo vigente"));
        return new Detail(value,calculation);
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Page driverCollections(UUID admin,UUID driver,Query query) {
        requireActor(admin,true);requireDriver(driver);return collections.list(null,driver,query);
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Metrics metrics(UUID admin,UUID driver,Period period) {
        requireActor(admin,true);requireDriver(driver);return collections.metrics(driver,period);
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Score score(UUID owner) { requireActor(owner,false);return collections.score(owner); }
    private void requireActor(UUID id,boolean admin) {
        if(!actors.activeActor(id,admin ? UserType.ADMIN : UserType.ESTABLISHMENT,false))
            throw new AccessDeniedException("Operação não autorizada");
    }
    private void requireDriver(UUID id) { if(!collections.driverExists(id)) throw new ResourceNotFoundException(); }
}
