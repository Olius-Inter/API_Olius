package br.com.olius.olius_core_api.controller;

import br.com.olius.olius_core_api.dto.PevResponses.AdminPage;
import br.com.olius.olius_core_api.dto.PevResponses.AdminPevDetail;
import br.com.olius.olius_core_api.dto.PevRequests.StatusChange;
import br.com.olius.olius_core_api.entity.enums.PevStatus;
import br.com.olius.olius_core_api.entity.enums.UserType;
import br.com.olius.olius_core_api.security.Caller;
import br.com.olius.olius_core_api.service.PevAdministrationService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/pevs")
@PreAuthorize("hasRole('ADMIN')")
public class PevAdministrationController {
    private final PevAdministrationService service;

    public PevAdministrationController(PevAdministrationService service) { this.service=service; }

    @GetMapping
    public AdminPage list(@RequestParam(required=false) PevStatus status,
                          @RequestParam(required=false) UserType ownerType,
                          @RequestParam(defaultValue="0") @Min(0) int page,
                          @RequestParam(defaultValue="20") @Min(1) @Max(100) int size,
                          Authentication authentication) {
        return service.list(status,ownerType,page,size,Caller.id(authentication));
    }

    @GetMapping("/{id}")
    public AdminPevDetail detail(@PathVariable UUID id, Authentication authentication) {
        return service.detail(id,Caller.id(authentication));
    }

    @PatchMapping("/{id}/status")
    public AdminPevDetail status(@PathVariable UUID id, @Valid @RequestBody StatusChange request,
                                 Authentication authentication) {
        return service.status(id,request.status(),Caller.id(authentication));
    }
}
