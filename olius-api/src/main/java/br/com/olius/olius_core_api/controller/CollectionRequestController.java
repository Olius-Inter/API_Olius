package br.com.olius.olius_core_api.controller;

import br.com.olius.olius_core_api.dto.CollectionRequestContracts.*;
import br.com.olius.olius_core_api.security.Caller;
import br.com.olius.olius_core_api.service.CollectionRequestService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/collection-requests")
@PreAuthorize("hasRole('ESTABLISHMENT')")
@Tag(name="Solicitações B2B",description="Pedidos e reservas do estabelecimento autenticado")
public class CollectionRequestController {
    private final CollectionRequestService service;
    public CollectionRequestController(CollectionRequestService service) { this.service=service; }
    @PostMapping
    @ApiResponse(responseCode="201",description="Solicitação criada ou recuperada por reenvio idempotente",
        content=@Content(mediaType="application/json",schema=@Schema(implementation=RequestView.class)))
    @Operation(summary="Criar solicitação e reservar litros e vaga",description="Exige ciclo vigente e franquia. Idempotency-Key UUID e dados originais devem ser preservados no reenvio. Retorna o mesmo pedido, no estado atual, sem nova reserva; Location identifica o recurso. Não registra coleta.")
    public ResponseEntity<RequestView> create(@Valid @RequestBody Create request,
            @RequestHeader("Idempotency-Key") UUID key,Authentication auth) {
        RequestView result=service.create(Caller.id(auth),request,key);
        return ResponseEntity.created(URI.create("/api/v1/collection-requests/"+result.id())).body(result);
    }
    @GetMapping
    @Operation(summary="Consultar próprios pedidos",description="Filtros por status, cycleId e requestAt no intervalo [from,until). Ordenação requestAt DESC,id DESC; page inicia em 0, size padrão 20 e máximo 100. Inclui pedidos antigos e atendidos; collectionId identifica coleta vinculada.")
    public Page list(@Valid @ModelAttribute Query query,Authentication auth) {
        return service.list(Caller.id(auth),false,null,query);
    }
    @GetMapping("/{id}")
    @Operation(summary="Consultar solicitação própria",description="Somente titular ativo. Pedidos de outro estabelecimento retornam 404. APPROVED não significa coleta pendente: consultar collectionId.")
    public RequestView detail(@PathVariable UUID id,Authentication auth) { return service.detail(id,Caller.id(auth),false); }
    @GetMapping("/{id}/schedule-history")
    @Operation(summary="Consultar histórico de agendamento",description="Histórico paginado do pedido próprio; ordenação changedAt DESC,id DESC. Não consulta logs internos de auditoria.")
    public SchedulePage history(@PathVariable UUID id,@Valid @ModelAttribute Pagination query,Authentication auth) {
        return service.history(id,Caller.id(auth),false,query);
    }
    @PostMapping("/{id}/cancellation")
    @Operation(summary="Cancelar solicitação própria",description="Motivo obrigatório. A política FREE ou LATE_FORFEITURE é calculada no PostgreSQL; o cliente não informa datas, iniciativa ou perda. Preserva ciclo de origem. Reenvio autorizado não duplica efeitos. Não permite cancelar após aceite ou coleta.")
    public RequestView cancel(@PathVariable UUID id,@Valid @RequestBody Reason request,Authentication auth) {
        return service.cancel(id,Caller.id(auth),false,request);
    }
}
