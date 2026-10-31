package br.com.olius.olius_core_api.controller;

import br.com.olius.olius_core_api.dto.PointsContracts.Summary;
import br.com.olius.olius_core_api.entity.enums.UserType;
import br.com.olius.olius_core_api.security.Caller;
import br.com.olius.olius_core_api.service.PointsService;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/users/me/points")
public class PointsController {
    private final PointsService service;
    public PointsController(PointsService service) { this.service=service; }

    @GetMapping
    public Summary own(@RequestParam(required=false) String page,
                       @RequestParam(required=false) String size,Authentication authentication) {
        if(Caller.type(authentication)!=UserType.CITIZENS)
            throw new AccessDeniedException("Pontuação disponível somente para o titular cidadão");
        return service.own(Caller.id(authentication),PevPagination.page(page),PevPagination.size(size));
    }
}
