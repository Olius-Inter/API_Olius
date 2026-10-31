package br.com.olius.olius_core_api.service;

import br.com.olius.olius_core_api.dto.DeliveryAdministrationContracts.Detail;
import br.com.olius.olius_core_api.dto.DeliveryAdministrationContracts.Page;
import br.com.olius.olius_core_api.exception.ResourceNotFoundException;
import br.com.olius.olius_core_api.repository.DeliveryAdministrationRepository;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class DeliveryAdministrationService {
    private final DeliveryAdministrationRepository deliveries;
    public DeliveryAdministrationService(DeliveryAdministrationRepository deliveries) {
        this.deliveries=deliveries;
    }

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Page list(UUID admin,int page,int size) {
        requireAdmin(admin);
        long offset=(long)page*size;
        if(offset>Integer.MAX_VALUE) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        return new Page(deliveries.page(size,offset),page,size,deliveries.count());
    }

    @Transactional(readOnly=true)
    public Detail detail(UUID admin,UUID id) {
        requireAdmin(admin);
        return deliveries.detail(id).orElseThrow(ResourceNotFoundException::new);
    }

    private void requireAdmin(UUID id) {
        if(!deliveries.activeAdmin(id)) throw new AccessDeniedException("ADMIN indisponível");
    }
}
