package br.com.olius.olius_core_api.service;

import br.com.olius.olius_core_api.dto.DeliveryContracts.Record;
import br.com.olius.olius_core_api.dto.DeliveryContracts.View;
import br.com.olius.olius_core_api.dto.DeliveryContracts.PevPage;
import br.com.olius.olius_core_api.entity.enums.UserStatus;
import br.com.olius.olius_core_api.entity.enums.UserType;
import br.com.olius.olius_core_api.exception.ResourceNotFoundException;
import br.com.olius.olius_core_api.repository.DeliveryOperationErrors;
import br.com.olius.olius_core_api.repository.DeliveryRepository;
import br.com.olius.olius_core_api.repository.PevRepository;
import br.com.olius.olius_core_api.repository.UserRepository;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class DeliveryService {
    private final DeliveryRepository deliveries;
    private final DeliveryOperationErrors errors;
    private final PevRepository pevs;
    private final UserRepository users;
    private final QrIdentificationService qr;
    private final AuditContext audit;

    public DeliveryService(DeliveryRepository deliveries,DeliveryOperationErrors errors,
                           PevRepository pevs,UserRepository users,
                           QrIdentificationService qr,AuditContext audit) {
        this.deliveries=deliveries;this.errors=errors;this.pevs=pevs;
        this.users=users;this.qr=qr;this.audit=audit;
    }

    @Transactional
    public Result register(UUID pevId,UUID validator,UserType actorType,Record input) {
        requireValidatorAndPev(pevId,validator,actorType);
        UUID internalKey=DeliveryKeys.internal(validator,input.idempotencyKey());
        deliveries.lock(internalKey);
        var previous=deliveries.existing(internalKey,validator);
        UUID citizen;
        boolean created=previous.isEmpty();
        if(created) {
            if(input.qrToken()==null)
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"QR obrigatório na criação");
            // A leitura do QR exige PEV aprovado. A recuperação não depende do estado atual.
            citizen=qr.identify(input.qrToken(),validator,actorType).id();
        } else {
            citizen=previous.get().citizenId();
        }
        audit.user(validator,"Registro de entrega B2C em PEV");
        UUID id;
        try {
            id=deliveries.record(internalKey,citizen,pevId,validator,
                input.oilVolumeLiters(),input.deliveryDate());
        } catch(DataAccessException failure) {
            throw errors.translate(failure);
        }
        View view=deliveries.view(id,validator)
            .orElseThrow(()->new IllegalStateException("Entrega confirmada sem projeção vigente"));
        return new Result(view,created);
    }

    private void requireValidatorAndPev(UUID pevId,UUID validator,UserType actorType) {
        if(actorType!=UserType.CITIZENS && actorType!=UserType.ESTABLISHMENT)
            throw new AccessDeniedException("Perfil sem permissão de PEV");
        var user=users.findById(validator)
            .orElseThrow(()->new AccessDeniedException("Responsável indisponível"));
        if(user.getStatus()!=UserStatus.ACTIVE || user.getUserType()!=actorType)
            throw new AccessDeniedException("Responsável indisponível");
        var pev=pevs.findById(pevId).orElseThrow(ResourceNotFoundException::new);
        UUID owner=actorType==UserType.CITIZENS ? pev.getCitizenId() : pev.getEstablishmentId();
        if(!validator.equals(owner)) throw new AccessDeniedException("PEV de outro responsável");
        // Estado APPROVED é validado na criação pela leitura do QR e pela procedure.
        // Não bloquear aqui a recuperação de uma entrega anterior à inativação.
    }

    public record Result(View delivery,boolean created) { }

    @Transactional(readOnly=true)
    public View detail(UUID pevId,UUID deliveryId,UUID validator,UserType actorType) {
        requireValidatorAndPev(pevId,validator,actorType);
        View view=deliveries.view(deliveryId,validator).orElseThrow(ResourceNotFoundException::new);
        if(!view.pevId().equals(pevId)) throw new ResourceNotFoundException();
        return view;
    }

    @Transactional(readOnly=true)
    public PevPage listForPev(UUID pevId,UUID validator,UserType actorType,int page,int size) {
        requireValidatorAndPev(pevId,validator,actorType);
        long offset=(long)page*size;
        if(offset>Integer.MAX_VALUE)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Página fora do limite");
        return new PevPage(deliveries.pageForPev(pevId,size,offset),page,size,
            deliveries.countForPev(pevId));
    }
}
