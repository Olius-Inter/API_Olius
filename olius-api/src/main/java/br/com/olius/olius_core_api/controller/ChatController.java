package br.com.olius.olius_core_api.controller;

import br.com.olius.olius_core_api.dto.ChatContracts.*;
import br.com.olius.olius_core_api.integration.ia.IaClient;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/chat")
public class ChatController {
    private final IaClient ia;
    public ChatController(IaClient ia) { this.ia = ia; }

    @PostMapping
    public ResponseEntity<ChatResponse> chat(@Valid @RequestBody ChatRequest request, Authentication auth) {
        return noStore(ia.chat(request, token(auth)));
    }

    @PostMapping("/sessions/{sessionId}/iniciar")
    public ResponseEntity<SessionResponse> start(@PathVariable UUID sessionId, Authentication auth) {
        return noStore(ia.session(sessionId, true, token(auth)));
    }

    @PostMapping("/sessions/{sessionId}/encerrar")
    public ResponseEntity<SessionResponse> end(@PathVariable UUID sessionId, Authentication auth) {
        return noStore(ia.session(sessionId, false, token(auth)));
    }

    private String token(Authentication auth) {
        if (!(auth instanceof JwtAuthenticationToken jwt) || !jwt.isAuthenticated()) {
            throw new BadCredentialsException("Autenticação necessária");
        }
        return jwt.getToken().getTokenValue();
    }

    private <T> ResponseEntity<T> noStore(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }
}
