package br.com.olius.olius_core_api.controller;

import br.com.olius.olius_core_api.dto.CollectionContracts.*;
import br.com.olius.olius_core_api.security.Caller;
import br.com.olius.olius_core_api.service.CollectionOperationsService;
import br.com.olius.olius_core_api.service.CollectionReadService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/v1/admin") @PreAuthorize("hasRole('ADMIN')")
@Tag(name="Gestão das coletas B2B")
public class CollectionAdministrationController {
    private final CollectionOperationsService operations;
    private final CollectionReadService reads;
    public CollectionAdministrationController(CollectionOperationsService operations,CollectionReadService reads) { this.operations=operations;this.reads=reads; }
    @PostMapping("/collection-requests/{id}/failed-visit")
    @Operation(summary="Registrar visita sem atendimento",description="Somente ADMIN ativo após chegada, sem aceite. reasonCode ESTABLISHMENT_CLOSED ou NO_RESPONSIBLE_AVAILABLE e justification obrigatória. Banco define motorista/data pela chegada, volumes zero, penalidade única e consumo de uma vaga/zero litros. Não cobre recusa explícita do aceite. Mesmo autor/motivo/justificativa retorna registro existente; divergência conflita. Retorna 200. Não cria aceite fictício.")
    public Detail failed(@PathVariable UUID id,@Valid @RequestBody FailedVisit request,Authentication auth) {
        return operations.failedVisit(id,Caller.id(auth),request);
    }
    @GetMapping("/collections")
    @Operation(summary="Listar coletas da operação",description="Filtros opcionais establishmentId, driverId, result, recordStatus, período físico [from,until) e paginação. Sem dados pessoais de motorista no resultado.")
    public Page list(@RequestParam(required=false) UUID establishmentId,@RequestParam(required=false) UUID driverId,
            @Valid @ModelAttribute Query query,Authentication auth) { return reads.list(Caller.id(auth),true,establishmentId,driverId,query); }
    @GetMapping("/collections/{id}")
    @Operation(summary="Consultar coleta e componentes atuais",description="Não expõe logs. Revisão da coleta e revisão do cálculo são independentes; somente o cálculo vigente é retornado.")
    public Detail detail(@PathVariable UUID id,Authentication auth) { return reads.detail(id,Caller.id(auth),true); }
    @GetMapping("/drivers/{id}/collections")
    @Operation(summary="Consultar visitas de um motorista",description="Inclui anuladas identificadas quando não há filtro; motorista inativo conserva seu histórico. Período físico [from,until), paginação e filtros de resultado/status.")
    public Page driverCollections(@PathVariable UUID id,@Valid @ModelAttribute Query query,Authentication auth) { return reads.driverCollections(Caller.id(auth),id,query); }
    @GetMapping("/drivers/{id}/metrics")
    @Operation(summary="Consultar indicadores de motorista",description="Somente RECORDED no período físico [from,until); sem datas considera todo o histórico. Cada visita conta uma vez, inclusive recolhimento zero; múltiplos motivos não multiplicam contagens. Volume efetivo inclui malsucedidas. Não representa disponibilidade ou culpa do motorista.")
    public Metrics metrics(@PathVariable UUID id,@Valid @ModelAttribute Period period,Authentication auth) { return reads.metrics(Caller.id(auth),id,period); }
}
