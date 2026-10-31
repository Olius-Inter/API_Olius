package br.com.olius.olius_core_api.service;

import br.com.olius.olius_core_api.dto.DeliveryAdministrationContracts.Detail;
import br.com.olius.olius_core_api.dto.DeliveryCorrectionContracts.Correction;
import br.com.olius.olius_core_api.entity.enums.RecordStatus;
import br.com.olius.olius_core_api.exception.BusinessRuleException;
import br.com.olius.olius_core_api.exception.ResourceNotFoundException;
import br.com.olius.olius_core_api.repository.DeliveryAdministrationRepository;
import br.com.olius.olius_core_api.repository.DeliveryCorrectionRepository;
import java.util.UUID;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DeliveryCorrectionService {
    private final DeliveryAdministrationRepository deliveries;
    private final DeliveryCorrectionRepository corrections;
    private final AuditContext audit;

    public DeliveryCorrectionService(DeliveryAdministrationRepository deliveries,
            DeliveryCorrectionRepository corrections, AuditContext audit) {
        this.deliveries=deliveries;
        this.corrections=corrections;
        this.audit=audit;
    }

    @Transactional
    public Detail change(UUID deliveryId, UUID adminId, Correction input) {
        if(!deliveries.activeAdmin(adminId)) throw new AccessDeniedException("ADMIN indisponível");

        var current=deliveries.detail(deliveryId).orElseThrow(ResourceNotFoundException::new).delivery();
        if(current.revision()!=input.expectedRevision())
            throw new OptimisticLockingFailureException("Revisão desatualizada");
        boolean correction=current.recordStatus()==RecordStatus.RECORDED
                && input.recordStatus()==RecordStatus.RECORDED;
        boolean annulment=current.recordStatus()==RecordStatus.RECORDED
                && input.recordStatus()==RecordStatus.ANNULLED;
        boolean restoration=current.recordStatus()==RecordStatus.ANNULLED
                && input.recordStatus()==RecordStatus.RECORDED;
        if(!correction && !annulment && !restoration)
            throw new BusinessRuleException(BusinessRuleException.Reason.STATE_CONFLICT);
        if(input.recordStatus()==RecordStatus.RECORDED
                && input.deliveryDate().isAfter(current.createdAt()))
            throw new BusinessRuleException(BusinessRuleException.Reason.INCONSISTENT_DATA);

        audit.user(adminId,input.reason().trim());
        // A procedure repete a conferência de ADMIN e revisão sob bloqueio.
        return corrections.applyAndRead(deliveryId,adminId,input);
    }
}
