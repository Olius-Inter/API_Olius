package br.com.olius.olius_core_api.service;

import br.com.olius.olius_core_api.dto.CollectionContracts.*;
import br.com.olius.olius_core_api.dto.CollectionOperationalHistoryContracts.OperationalHistory;
import br.com.olius.olius_core_api.dto.CollectionCorrectionContracts.*;
import br.com.olius.olius_core_api.entity.enums.*;
import br.com.olius.olius_core_api.exception.*;
import br.com.olius.olius_core_api.repository.*;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class CollectionCorrectionService {
    private final CollectionOperationalHistoryRepository operationalHistory;
    private final CollectionCorrectionRepository corrections;
    private final CollectionReadRepository collections;
    private final CollectionRequestRepository actors;
    private final CollectionReadService reads;
    private final AuditContext audit;
    private final CollectionOperationErrors errors;
    public CollectionCorrectionService(CollectionCorrectionRepository corrections,CollectionReadRepository collections,
            CollectionRequestRepository actors,CollectionReadService reads,AuditContext audit,CollectionOperationErrors errors,CollectionOperationalHistoryRepository operationalHistory) {
        this.operationalHistory=operationalHistory;
        this.corrections=corrections;this.collections=collections;this.actors=actors;
        this.reads=reads;this.audit=audit;this.errors=errors;
    }

    @Transactional
    public Detail correct(UUID id,UUID admin,Correction input) {
        return change(id,admin,input.expectedRevision(),input.justification(),RecordStatus.RECORDED,RecordStatus.RECORDED,input);
    }
    @Transactional
    public Detail annul(UUID id,UUID admin,Annulment input) {
        return change(id,admin,input.expectedRevision(),input.justification(),RecordStatus.RECORDED,RecordStatus.ANNULLED,null);
    }
    @Transactional
    public Detail restore(UUID id,UUID admin,Correction input) {
        return change(id,admin,input.expectedRevision(),input.justification(),RecordStatus.ANNULLED,RecordStatus.RECORDED,input);
    }

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public History history(UUID id,UUID admin,HistoryQuery query) {
        requireAdmin(admin,false);requireCollection(id);return corrections.history(id,query);
    }

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public OperationalHistory operationalHistory(UUID id,UUID admin,HistoryQuery query) {
        requireAdmin(admin,false);
        try { return operationalHistory.history(id,admin,query); }
        catch(DataAccessException failure) { throw errors.translate(failure,CollectionOperationErrors.Operation.HISTORY); }
    }

    private Detail change(UUID id,UUID admin,int revision,String reason,RecordStatus expected,RecordStatus target,Correction facts) {
        requireAdmin(admin,true);
        var initial=requireCollection(id);corrections.lockEstablishment(initial.establishmentId());
        var current=requireCollection(id);
        if(current.revision()!=revision) throw new OptimisticLockingFailureException("Revisão desatualizada");
        if(current.recordStatus()!=expected) throw new BusinessRuleException(BusinessRuleException.Reason.STATE_CONFLICT);
        if(facts!=null) validateFacts(facts);
        audit.user(admin,reason.trim());
        try { corrections.apply(id,admin,revision,reason.trim(),target,facts); }
        catch(DataAccessException failure) { throw errors.translate(failure,CollectionOperationErrors.Operation.CORRECTION); }
        return reads.uncheckedDetail(id,null);
    }

    private void validateFacts(Correction facts) {
        boolean noOil=facts.presentedVolumeLiters().signum()==0;
        boolean noteRequired=noOil || facts.oilCondition()==OilCondition.UNACCEPTABLE || facts.hasCompromisingOccurrence();
        if(facts.collectedVolumeLiters().compareTo(facts.presentedVolumeLiters())>0
                || noOil!=(facts.oilCondition()==OilCondition.NOT_ASSESSED)
                || noteRequired && (facts.observation()==null || facts.observation().isBlank()))
            throw new BusinessRuleException(BusinessRuleException.Reason.INCONSISTENT_DATA);
    }
    private View requireCollection(UUID id) { return collections.find(id,null).orElseThrow(ResourceNotFoundException::new); }
    private void requireAdmin(UUID id,boolean lock) {
        if(!actors.activeActor(id,UserType.ADMIN,lock)) throw new AccessDeniedException("Operação não autorizada");
    }
}
