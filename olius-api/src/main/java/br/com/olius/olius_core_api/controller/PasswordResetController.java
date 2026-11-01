package br.com.olius.olius_core_api.controller;

import br.com.olius.olius_core_api.dto.PasswordResetRequests;
import br.com.olius.olius_core_api.service.PasswordResetService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class PasswordResetController {
    public record Accepted(String message) { }
    private final PasswordResetService service;
    public PasswordResetController(PasswordResetService service) { this.service=service; }
    @Operation(summary="Solicitar recuperação de senha",description="Resposta genérica; não revela existência, status ou limite por conta. Processamento assíncrono, sem garantia de entrega do e-mail.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="202",description="Solicitação recebida; resposta genérica sem confirmação de entrega")
    @PostMapping("/password-reset-requests")
    public ResponseEntity<Accepted> request(@Valid @RequestBody PasswordResetRequests.Request request) {
        service.request(request);
        return ResponseEntity.accepted().cacheControl(CacheControl.noStore())
            .body(new Accepted("Se a conta estiver disponível, enviaremos as instruções de recuperação."));
    }
    @Operation(summary="Definir nova senha",description="Token de uso único. A conclusão revoga todas as sessões; não autentica automaticamente. Faça login novamente.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="204",description="Senha redefinida e sessões revogadas; faça login novamente")
    @PostMapping("/password-resets")
    public ResponseEntity<Void> complete(@Valid @RequestBody PasswordResetRequests.Complete request) {
        service.complete(request);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }
}
