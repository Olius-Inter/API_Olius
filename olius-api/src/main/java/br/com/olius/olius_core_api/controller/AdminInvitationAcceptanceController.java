package br.com.olius.olius_core_api.controller;

import br.com.olius.olius_core_api.dto.AdminInvitationRequests.Accept;
import br.com.olius.olius_core_api.service.AdminInvitationService;
import jakarta.validation.Valid;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth/admin-invitations")
public class AdminInvitationAcceptanceController {
    private final AdminInvitationService service;

    public AdminInvitationAcceptanceController(AdminInvitationService service) { this.service=service; }

    @PostMapping("/acceptance")
    public ResponseEntity<Void> accept(@Valid @RequestBody Accept request) {
        service.accept(request);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }
}
