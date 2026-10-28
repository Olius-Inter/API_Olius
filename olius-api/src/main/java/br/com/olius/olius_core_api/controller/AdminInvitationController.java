package br.com.olius.olius_core_api.controller;

import br.com.olius.olius_core_api.dto.AdminInvitationRequests;
import br.com.olius.olius_core_api.dto.AdminInvitationResponses.*;
import br.com.olius.olius_core_api.security.Caller;
import br.com.olius.olius_core_api.service.AdminInvitationService;
import br.com.olius.olius_core_api.service.AdminInvitationTransactions;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/invitations")
@PreAuthorize("hasRole('ADMIN')")
public class AdminInvitationController {
    private final AdminInvitationService service;
    private final AdminInvitationTransactions transactions;

    public AdminInvitationController(AdminInvitationService service,AdminInvitationTransactions transactions) {
        this.service=service; this.transactions=transactions;
    }

    /** Reenviar é outra emissão para o mesmo e-mail, sempre com novo segredo. */
    @PostMapping
    public ResponseEntity<Invitation> issue(@Valid @RequestBody AdminInvitationRequests.Issue request,Authentication auth) {
        var invitation=service.issue(Caller.id(auth),request);
        return ResponseEntity.created(URI.create("/api/v1/admin/invitations/"+invitation.id()))
                .cacheControl(CacheControl.noStore()).body(invitation);
    }

    @GetMapping
    public ResponseEntity<Page> list(@RequestParam(required=false) State state,
            @RequestParam(defaultValue="0") @Min(0) int page,
            @RequestParam(defaultValue="20") @Min(1) @Max(100) int size,Authentication auth) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(transactions.list(Caller.id(auth),state,page,size));
    }

    @GetMapping("/{id}")
    public ResponseEntity<Invitation> get(@PathVariable UUID id,Authentication auth) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(transactions.get(Caller.id(auth),id));
    }

    @PostMapping("/{id}/revocation")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(@PathVariable UUID id,Authentication auth) {
        transactions.revoke(Caller.id(auth),id);
    }
}
