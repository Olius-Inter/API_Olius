package br.com.olius.olius_core_api.controller;
import br.com.olius.olius_core_api.dto.IdentityRequests.*;
import br.com.olius.olius_core_api.dto.IdentityResponses.*;
import br.com.olius.olius_core_api.repository.*;
import br.com.olius.olius_core_api.security.*;
import br.com.olius.olius_core_api.service.*;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api/v1")
public class IdentityController {
    private final RegistrationService registrations; private final ProfileService profiles;
    private final EstablishmentTypeRepository types; private final AuthSessionRepository sessions;
    public IdentityController(RegistrationService registrations,ProfileService profiles,
        EstablishmentTypeRepository types,AuthSessionRepository sessions) {
        this.registrations=registrations; this.profiles=profiles; this.types=types; this.sessions=sessions;
    }
    @PostMapping("/auth/registrations/citizens")
    public ResponseEntity<Registration> citizen(@Valid @RequestBody CitizenRegistration request) {
        var result=registrations.citizen(request); return ResponseEntity.created(URI.create("/api/v1/users/"+result.id())).body(result);
    }
    @PostMapping("/auth/registrations/establishments")
    public ResponseEntity<Registration> establishment(@Valid @RequestBody EstablishmentRegistration request) {
        var result=registrations.establishment(request); return ResponseEntity.created(URI.create("/api/v1/users/"+result.id())).body(result);
    }
    @GetMapping("/users/me") public Profile me(Authentication auth) { return profiles.get(Caller.id(auth),Caller.id(auth),Caller.type(auth)); }
    @GetMapping("/users/{id}") public Profile user(@PathVariable UUID id,Authentication auth) { return profiles.get(id,Caller.id(auth),Caller.type(auth)); }
    @GetMapping("/establishment-types") public List<TypeView> types() {
        return types.findAll(org.springframework.data.domain.Sort.by("name")).stream()
            .map(t -> new TypeView(t.getId(),t.getName(),t.getDescription())).toList();
    }
    /** A segunda API encaminha o JWT do usuário; a Core verifica sub/sid e o estado atual. */
    @GetMapping("/auth/session") public SessionIdentity session(Authentication auth) {
        var id=Caller.id(auth); var sid=Caller.session(auth); var state=sessions.status(id,sid);
        if(!state.valid()) throw new org.springframework.security.authentication.BadCredentialsException("Sessão inválida");
        return new SessionIdentity(id,sid,state.type(),state.expiresAt());
    }
}
