package br.com.olius.olius_core_api.controller;

import br.com.olius.olius_core_api.dto.NotificationContracts.*;
import br.com.olius.olius_core_api.security.Caller;
import br.com.olius.olius_core_api.service.NotificationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/notifications")
@Tag(name = "Notificações")
public class NotificationController {
    private final NotificationService service;

    public NotificationController(NotificationService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "Listar avisos da conta autenticada")
    public ResponseEntity<Page> list(@Valid @ModelAttribute Query query, Authentication auth) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(service.list(Caller.id(auth), query));
    }

    @GetMapping("/unread-count")
    @Operation(summary = "Contar avisos não lidos da conta autenticada")
    public ResponseEntity<Count> unreadCount(Authentication auth) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new Count(service.unreadCount(Caller.id(auth))));
    }

    @PutMapping("/{id}/read")
    @Operation(summary = "Marcar um aviso próprio como lido, preservando a primeira leitura")
    public ResponseEntity<Void> markRead(@PathVariable UUID id, Authentication auth) {
        service.markRead(Caller.id(auth), id);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }
}
