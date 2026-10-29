package br.com.olius.olius_core_api.controller;

import br.com.olius.olius_core_api.dto.CollectionRequestContracts.*;
import br.com.olius.olius_core_api.security.Caller;
import br.com.olius.olius_core_api.service.CollectionRequestService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/collection-requests")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name="Gestão de solicitações B2B",description="Análise e agenda pelo administrador ativo")
public class CollectionRequestAdministrationController {
    private final CollectionRequestService service;
    public CollectionRequestAdministrationController(CollectionRequestService service) { this.service=service; }
    @GetMapping
    @Operation(summary="Listar pedidos da operação",description="Consulta paginada; filtros por establishmentId, cycleId, status e requestAt em [from,until). Ordem requestAt DESC,id DESC. Não retorna CPF, CNPJ, contatos ou QR Codes.")
    public Page list(@RequestParam(required=false) UUID establishmentId,@Valid @ModelAttribute Query query,Authentication auth) {
        return service.list(Caller.id(auth),true,establishmentId,query);
    }
    @GetMapping("/{id}")
    @Operation(summary="Consultar detalhe administrativo",description="Consulta dados operacionais, ciclo original e collectionId, sem expor logs internos.")
    public RequestView detail(@PathVariable UUID id,Authentication auth) { return service.detail(id,Caller.id(auth),true); }
    @GetMapping("/{id}/schedule-history")
    @Operation(summary="Consultar histórico administrativo de agenda",description="Histórico paginado, por changedAt DESC,id DESC. Inclui horário anterior, novo, acordo, autor e justificativa.")
    public SchedulePage history(@PathVariable UUID id,@Valid @ModelAttribute Pagination query,Authentication auth) {
        return service.history(id,Caller.id(auth),true,query);
    }
    @PostMapping("/{id}/scheduling")
    @Operation(summary="Agendar ou reagendar coleta",description="Corpo: scheduledAt, agreedAt e reason. Datas ISO-8601 com offset ou UTC. O acordo não pode estar no futuro. Aprova PENDING com horário; reagenda APPROVED sem chegada/coleta. Reenvio do mesmo horário não duplica histórico. Acordo é feito externamente.")
    public RequestView schedule(@PathVariable UUID id,@Valid @RequestBody Scheduling request,Authentication auth) {
        return service.schedule(id,Caller.id(auth),request);
    }
    @PostMapping("/{id}/rejection")
    @Operation(summary="Rejeitar pedido pendente",description="Somente PENDING sem atendimento. Motivo obrigatório é preservado na auditoria; não fica disponível como campo de leitura pela Core. Rejeição repetida conflita. Libera a reserva, sem gerar coleta.")
    public RequestView reject(@PathVariable UUID id,@Valid @RequestBody Reason request,Authentication auth) {
        return service.reject(id,Caller.id(auth),request);
    }
    @PostMapping("/{id}/cancellation")
    @Operation(summary="Cancelar por iniciativa operacional",description="Motivo obrigatório; iniciativa OPERATION controlada pelo servidor, sem perda de franquia. Não permite cancelar após aceite/coleta. Não representa cancelamento tardio em nome do estabelecimento.")
    public RequestView cancel(@PathVariable UUID id,@Valid @RequestBody Reason request,Authentication auth) {
        return service.cancel(id,Caller.id(auth),true,request);
    }
}
