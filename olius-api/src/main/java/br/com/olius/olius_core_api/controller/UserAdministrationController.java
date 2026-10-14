package br.com.olius.olius_core_api.controller;
import br.com.olius.olius_core_api.dto.IdentityRequests.*;
import br.com.olius.olius_core_api.dto.IdentityResponses.Registration;
import br.com.olius.olius_core_api.security.Caller;
import br.com.olius.olius_core_api.service.*;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api/v1/admin/users") @PreAuthorize("hasRole('ADMIN')")
public class UserAdministrationController {
    private final RegistrationService registrations;
    private final UserAdministrationService users;
    public UserAdministrationController(RegistrationService registrations,UserAdministrationService users) {
        this.registrations=registrations; this.users=users;
    }
    @PostMapping public ResponseEntity<Registration> admin(@Valid @RequestBody AdminRegistration request,Authentication auth) {
        var result=registrations.admin(request,Caller.id(auth));
        return ResponseEntity.created(URI.create("/api/v1/users/"+result.id())).body(result);
    }
    @PatchMapping("/{id}/status") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void status(@PathVariable UUID id,@Valid @RequestBody StatusChange request,Authentication auth) {
        users.status(id,request.status(),Caller.id(auth));
    }
}
