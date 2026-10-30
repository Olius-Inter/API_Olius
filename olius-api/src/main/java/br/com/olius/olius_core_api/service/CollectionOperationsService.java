package br.com.olius.olius_core_api.service;

import br.com.olius.olius_core_api.dto.CollectionContracts.*;
import br.com.olius.olius_core_api.dto.CollectionRequestContracts;
import br.com.olius.olius_core_api.entity.enums.*;
import br.com.olius.olius_core_api.exception.*;
import br.com.olius.olius_core_api.repository.*;
import br.com.olius.olius_core_api.repository.CollectionOperationErrors.Operation;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.dao.DataAccessException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CollectionOperationsService {
    private final CollectionOperationsRepository operations;
    private final CollectionRequestRepository requests;
    private final CollectionReadService reads;
    private final AuditContext audit;
    private final CollectionOperationErrors errors;
    public CollectionOperationsService(CollectionOperationsRepository operations,CollectionRequestRepository requests,
            CollectionReadService reads,AuditContext audit,CollectionOperationErrors errors) {
        this.operations=operations;this.requests=requests;this.reads=reads;this.audit=audit;this.errors=errors;
    }
    @Transactional(isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public Context context(DriverAccess access,int page,int size) { return operations.context(identify(access),page,size); }
    @Transactional
    public CollectionRequestContracts.RequestView arrival(UUID id,DriverAccess access) {
        var actor=identify(access);requireRequest(id,actor.establishmentId());
        audit.driver(actor.driverId(),"Chegada para coleta B2B");
        call(Operation.ARRIVAL,()->operations.arrival(id,actor.driverId()));
        return requireRequest(id,actor.establishmentId());
    }
    @Transactional
    public CollectionRequestContracts.RequestView accept(UUID id,UUID establishment) {
        requireActor(establishment,UserType.ESTABLISHMENT);requireRequest(id,establishment);
        audit.user(establishment,"Aceite do atendimento B2B");
        call(Operation.ACCEPTANCE,()->operations.accept(id,establishment));
        return requireRequest(id,establishment);
    }
    @Transactional
    public Detail registerCollection(UUID id,br.com.olius.olius_core_api.dto.CollectionContracts.Record input) {
        var actor=identify(input.access());requireRequest(id,actor.establishmentId());validateFacts(input);
        audit.driver(actor.driverId(),"Registro operacional de coleta B2B");
        UUID collection=call(Operation.RECORD,()->operations.registerCollection(id,actor.driverId(),input));
        return reads.uncheckedDetail(collection,actor.establishmentId());
    }
    @Transactional
    public Detail failedVisit(UUID id,UUID admin,FailedVisit input) {
        requireActor(admin,UserType.ADMIN);
        if(requests.find(id,null).isEmpty()) throw new ResourceNotFoundException();
        UUID collection=call(Operation.FAILED_VISIT,()->operations.failedVisit(id,admin,input));
        return reads.uncheckedDetail(collection,null);
    }
    private CollectionOperationsRepository.Identity identify(DriverAccess access) {
        return operations.identify(access).orElseThrow(()->new AccessDeniedException("Identificação operacional inválida"));
    }
    private CollectionRequestContracts.RequestView requireRequest(UUID id,UUID establishment) {
        return requests.find(id,establishment).orElseThrow(ResourceNotFoundException::new);
    }
    private void requireActor(UUID id,UserType type) {
        if(!requests.activeActor(id,type,true)) throw new AccessDeniedException("Operação não autorizada");
    }
    private void validateFacts(br.com.olius.olius_core_api.dto.CollectionContracts.Record input) {
        boolean noOil=input.presentedVolumeLiters().signum()==0;
        boolean observationRequired=noOil || input.oilCondition()==OilCondition.UNACCEPTABLE || input.hasCompromisingOccurrence();
        if(input.collectedVolumeLiters().compareTo(input.presentedVolumeLiters())>0
                || noOil!=(input.oilCondition()==OilCondition.NOT_ASSESSED)
                || observationRequired && (input.observation()==null || input.observation().isBlank()))
            throw new BusinessRuleException(BusinessRuleException.Reason.INCONSISTENT_DATA);
    }
    private <T> T call(Operation operation,Supplier<T> action) {
        try { return action.get(); } catch(DataAccessException failure) { throw errors.translate(failure,operation); }
    }
}
