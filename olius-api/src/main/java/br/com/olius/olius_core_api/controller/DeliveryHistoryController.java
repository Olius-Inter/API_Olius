package br.com.olius.olius_core_api.controller;

import br.com.olius.olius_core_api.dto.DeliveryHistoryContracts.Page;
import br.com.olius.olius_core_api.security.Caller;
import br.com.olius.olius_core_api.service.DeliveryHistoryService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/users/me/deliveries")
@PreAuthorize("hasRole('CITIZENS')")
public class DeliveryHistoryController {
    private final DeliveryHistoryService service;

    public DeliveryHistoryController(DeliveryHistoryService service) { this.service=service; }

    @GetMapping
    public Page own(@RequestParam(required=false) String page,@RequestParam(required=false) String size,
                    Authentication authentication) {
        return service.own(Caller.id(authentication),PevPagination.page(page),PevPagination.size(size));
    }
}
