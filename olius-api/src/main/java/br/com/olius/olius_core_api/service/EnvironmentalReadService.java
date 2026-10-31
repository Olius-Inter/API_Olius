package br.com.olius.olius_core_api.service;

import br.com.olius.olius_core_api.dto.CollectionContracts.Period;
import br.com.olius.olius_core_api.dto.EnvironmentalContracts.Summary;
import br.com.olius.olius_core_api.entity.enums.UserType;
import br.com.olius.olius_core_api.repository.CollectionRequestRepository;
import br.com.olius.olius_core_api.repository.EnvironmentalReadRepository;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class EnvironmentalReadService {
    private final CollectionRequestRepository actors;
    private final EnvironmentalReadRepository reads;
    public EnvironmentalReadService(CollectionRequestRepository actors,EnvironmentalReadRepository reads) { this.actors=actors;this.reads=reads; }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Summary summary(UUID actor,boolean administrative,Period period) {
        if(!actors.activeActor(actor,administrative ? UserType.ADMIN : UserType.ESTABLISHMENT,false))
            throw new AccessDeniedException("Operação não autorizada");
        return reads.summary(administrative ? null : actor,period,administrative);
    }
}
