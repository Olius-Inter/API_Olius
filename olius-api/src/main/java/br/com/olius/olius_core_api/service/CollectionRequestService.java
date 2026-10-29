package br.com.olius.olius_core_api.service;

import br.com.olius.olius_core_api.dto.CollectionRequestContracts.*;
import br.com.olius.olius_core_api.entity.enums.UserType;
import br.com.olius.olius_core_api.exception.ResourceNotFoundException;
import br.com.olius.olius_core_api.exception.BusinessRuleException;
import br.com.olius.olius_core_api.entity.enums.CollectionRequestStatus;
import br.com.olius.olius_core_api.repository.CollectionRequestRepository;
import br.com.olius.olius_core_api.repository.CollectionRoutineErrors;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.dao.DataAccessException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CollectionRequestService {
    private final CollectionRequestRepository requests;
    private final AuditContext audit;
    private final CollectionRoutineErrors errors;
    public CollectionRequestService(CollectionRequestRepository requests,AuditContext audit,CollectionRoutineErrors errors) {
        this.requests=requests; this.audit=audit; this.errors=errors;
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Availability currentAvailability(UUID actor) {
        requireActor(actor,false,false);
        UUID cycle=requests.currentCycle(actor).orElseThrow(ResourceNotFoundException::new);
        return requests.availability(cycle,actor).orElseThrow(ResourceNotFoundException::new);
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Availability availability(UUID cycle,UUID actor) {
        requireActor(actor,false,false);
        return requests.availability(cycle,actor).orElseThrow(ResourceNotFoundException::new);
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Page list(UUID actor,boolean administrative,UUID owner,Query query) {
        requireActor(actor,administrative,false);
        return requests.list(administrative ? owner : actor,query);
    }
    @Transactional(readOnly=true)
    public RequestView detail(UUID id,UUID actor,boolean administrative) {
        requireActor(actor,administrative,false);
        return find(id,actor,administrative);
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public SchedulePage history(UUID id,UUID actor,boolean administrative,Pagination query) {
        requireActor(actor,administrative,false);
        find(id,actor,administrative);
        return requests.history(id,query.page(),query.size());
    }
    @Transactional
    public RequestView create(UUID actor,Create request,UUID key) {
        requireActor(actor,false,true);
        audit.user(actor,"Solicitação de coleta B2B");
        UUID id=call(()->requests.create(actor,request,key));
        return find(id,actor,false);
    }
    @Transactional
    public RequestView schedule(UUID id,UUID actor,Scheduling request) {
        requireActor(actor,true,true);
        var current=find(id,actor,true);
        if(request.scheduledAt().isBefore(current.requestAt()))
            throw new BusinessRuleException(BusinessRuleException.Reason.INCONSISTENT_DATA);
        audit.user(actor,request.reason());
        call(()->{ requests.schedule(id,actor,request); return null; });
        return find(id,actor,true);
    }
    @Transactional
    public RequestView reject(UUID id,UUID actor,Reason request) {
        requireActor(actor,true,true);
        var current=find(id,actor,true);
        if(current.status()!=CollectionRequestStatus.PENDING || current.arrival()!=null
                || current.acceptance()!=null || current.collectionId()!=null)
            throw new BusinessRuleException(BusinessRuleException.Reason.STATE_CONFLICT);
        audit.user(actor,request.reason());
        try { requests.reject(id,actor,request.reason()); }
        catch(DataAccessException failure) { throw errors.translateRejection(failure); }
        return find(id,actor,true);
    }
    @Transactional
    public RequestView cancel(UUID id,UUID actor,boolean administrative,Reason request) {
        requireActor(actor,administrative,true);
        find(id,actor,administrative);
        audit.user(actor,request.reason());
        call(()->{ requests.cancel(id,actor,administrative,request.reason()); return null; });
        return find(id,actor,administrative);
    }
    private RequestView find(UUID id,UUID actor,boolean administrative) {
        return requests.find(id,administrative ? null : actor).orElseThrow(ResourceNotFoundException::new);
    }
    private void requireActor(UUID actor,boolean administrative,boolean lock) {
        UserType expected=administrative ? UserType.ADMIN : UserType.ESTABLISHMENT;
        if(!requests.activeActor(actor,expected,lock)) throw new AccessDeniedException("Operação não autorizada");
    }
    private <T> T call(Supplier<T> operation) {
        try { return operation.get(); }
        catch(DataAccessException failure) { throw errors.translate(failure); }
    }
}
