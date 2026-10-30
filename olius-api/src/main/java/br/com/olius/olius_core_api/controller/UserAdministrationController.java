package br.com.olius.olius_core_api.controller;

import br.com.olius.olius_core_api.dto.IdentityRequests.StatusChange;
import br.com.olius.olius_core_api.security.Caller;
import br.com.olius.olius_core_api.service.UserAdministrationService;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/users")
@PreAuthorize("hasRole('ADMIN')")
public class UserAdministrationController {
    private final UserAdministrationService users;
    public UserAdministrationController(UserAdministrationService users) { this.users=users; }

    @PatchMapping("/{id}/status")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void status(@PathVariable UUID id,@Valid @RequestBody StatusChange request,Authentication auth) {
        users.status(id,request.status(),Caller.id(auth));
    }
}
