package br.com.olius.olius_core_api.service;

import br.com.olius.olius_core_api.dto.DeliveryHistoryContracts.Page;
import br.com.olius.olius_core_api.entity.enums.UserStatus;
import br.com.olius.olius_core_api.entity.enums.UserType;
import br.com.olius.olius_core_api.repository.DeliveryHistoryRepository;
import br.com.olius.olius_core_api.repository.UserRepository;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class DeliveryHistoryService {
    private final DeliveryHistoryRepository deliveries;
    private final UserRepository users;

    public DeliveryHistoryService(DeliveryHistoryRepository deliveries,UserRepository users) {
        this.deliveries=deliveries;
        this.users=users;
    }

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Page own(UUID citizen,int page,int size) {
        var user=users.findById(citizen).orElseThrow(()->new AccessDeniedException("Cidadão indisponível"));
        if(user.getUserType()!=UserType.CITIZENS || user.getStatus()!=UserStatus.ACTIVE)
            throw new AccessDeniedException("Histórico disponível somente ao cidadão ativo");
        long offset=(long)page*size;
        if(page<0 || size<1 || size>100 || offset>Integer.MAX_VALUE)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        return new Page(deliveries.page(citizen,size,offset),page,size,deliveries.count(citizen));
    }
}
