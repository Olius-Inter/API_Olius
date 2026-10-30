package br.com.olius.olius_core_api.controller;

import br.com.olius.olius_core_api.dto.CollectionRequestContracts.Availability;
import br.com.olius.olius_core_api.security.Caller;
import br.com.olius.olius_core_api.service.CollectionRequestService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/billing/cycles")
@PreAuthorize("hasRole('ESTABLISHMENT')")
@Tag(name="Franquia B2B")
public class CycleAvailabilityController {
    private final CollectionRequestService service;
    public CycleAvailabilityController(CollectionRequestService service) { this.service=service; }
    @GetMapping("/current/availability")
    @Operation(summary="Consultar disponibilidade do ciclo atual",description="Identifica o ciclo próprio vigente sem exigir seu ID. Não cria assinatura ou reserva. Se não houver ciclo vigente retorna 404; se a assinatura não estiver ativa, os indicadores refletem esse estado e novos pedidos continuam bloqueados.")
    public Availability current(Authentication auth) { return service.currentAvailability(Caller.id(auth)); }
    @GetMapping("/{cycleId}/availability")
    @Operation(summary="Consultar disponibilidade do ciclo próprio",description="Consulta atual ou histórica da franquia, separando reserva, consumo e perda. Um ciclo vencido não permite novos pedidos. Consulta não reserva recursos; a criação revalida e reserva atomicamente. Ciclo inexistente ou de outro titular retorna 404.")
    public Availability availability(@PathVariable UUID cycleId,Authentication auth) { return service.availability(cycleId,Caller.id(auth)); }
}
