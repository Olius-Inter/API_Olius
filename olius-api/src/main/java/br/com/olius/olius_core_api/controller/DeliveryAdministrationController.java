package br.com.olius.olius_core_api.controller;

import br.com.olius.olius_core_api.dto.DeliveryAdministrationContracts.Detail;
import br.com.olius.olius_core_api.dto.DeliveryAdministrationContracts.Page;
import br.com.olius.olius_core_api.security.Caller;
import br.com.olius.olius_core_api.service.DeliveryAdministrationService;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/deliveries")
@PreAuthorize("hasRole('ADMIN')")
public class DeliveryAdministrationController {
    private final DeliveryAdministrationService service;
    public DeliveryAdministrationController(DeliveryAdministrationService service) {
        this.service=service;
    }

    @GetMapping
    public Page list(@RequestParam(required=false) String page,
                     @RequestParam(required=false) String size,Authentication authentication) {
        return service.list(Caller.id(authentication),PevPagination.page(page),PevPagination.size(size));
    }

    @GetMapping("/{id}")
    public Detail detail(@PathVariable UUID id,Authentication authentication) {
        return service.detail(Caller.id(authentication),id);
    }
}
