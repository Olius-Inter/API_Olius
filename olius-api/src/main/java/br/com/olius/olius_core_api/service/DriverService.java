package br.com.olius.olius_core_api.service;

import br.com.olius.olius_core_api.dto.DriverContracts.*;
import br.com.olius.olius_core_api.entity.Driver;
import br.com.olius.olius_core_api.entity.enums.UserType;
import br.com.olius.olius_core_api.exception.ResourceNotFoundException;
import br.com.olius.olius_core_api.repository.CollectionRequestRepository;
import br.com.olius.olius_core_api.repository.DriverRepository;
import jakarta.persistence.EntityManager;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DriverService {
    private final DriverRepository drivers;
    private final CollectionRequestRepository actors;
    private final EntityManager entities;
    public DriverService(DriverRepository drivers,CollectionRequestRepository actors,EntityManager entities) {
        this.drivers=drivers;this.actors=actors;this.entities=entities;
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Page list(UUID admin,Query query) {
        requireAdmin(admin,false);
        var pagination=PageRequest.of(query.page(),query.size(),Sort.by("name","id"));
        var page=query.status()==null ? drivers.findAll(pagination) : drivers.findByStatus(query.status(),pagination);
        return new Page(page.getContent().stream().map(this::view).toList(),query.page(),query.size(),page.getTotalElements());
    }
    @Transactional(readOnly=true)
    public View detail(UUID admin,UUID id) {
        requireAdmin(admin,false);
        return view(drivers.findById(id).orElseThrow(ResourceNotFoundException::new));
    }
    @Transactional
    public View create(UUID admin,Create input) {
        requireAdmin(admin,true);
        // persist não faz merge e respeita o INSERT restrito às quatro colunas autorizadas.
        var driver=new Driver(UUID.randomUUID(),input.name(),input.cpf(),input.cnh());
        entities.persist(driver);entities.flush();entities.refresh(driver);
        return view(driver);
    }
    @Transactional
    public View update(UUID admin,UUID id,Update input) {
        requireAdmin(admin,true);
        var driver=drivers.findForUpdate(id).orElseThrow(ResourceNotFoundException::new);
        driver.update(input.name(),input.cpf(),input.cnh());
        entities.flush();entities.refresh(driver);return view(driver);
    }
    @Transactional
    public View status(UUID admin,UUID id,StatusChange input) {
        requireAdmin(admin,true);
        var driver=drivers.findForUpdate(id).orElseThrow(ResourceNotFoundException::new);
        driver.changeStatus(input.status());entities.flush();entities.refresh(driver);return view(driver);
    }
    private void requireAdmin(UUID id,boolean lock) {
        if(!actors.activeActor(id,UserType.ADMIN,lock)) throw new AccessDeniedException("Operação não autorizada");
    }
    private View view(Driver driver) {
        return new View(driver.getId(),driver.getName(),driver.getCpf(),driver.getCnh(),driver.getStatus(),
                driver.getRegistrationDate(),driver.getUpdatedAt());
    }
}
