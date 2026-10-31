package br.com.olius.olius_core_api.controller;

import br.com.olius.olius_core_api.dto.CollectionContracts.Detail;
import br.com.olius.olius_core_api.dto.CollectionOperationalHistoryContracts.OperationalHistory;
import br.com.olius.olius_core_api.dto.CollectionCorrectionContracts.*;
import br.com.olius.olius_core_api.security.Caller;
import br.com.olius.olius_core_api.service.CollectionCorrectionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/v1/admin/collections") @PreAuthorize("hasRole('ADMIN')")
@Tag(name="Correções administrativas B2B")
public class CollectionCorrectionController {
    private final CollectionCorrectionService service;
    public CollectionCorrectionController(CollectionCorrectionService service) { this.service=service; }

    @PostMapping("/{id}/corrections")
    @Operation(summary="Corrigir coleta vigente",description="ADMIN ativo; expectedRevision e justificativa obrigatórias. Não altera motorista, solicitação, ciclo ou horário físico. Banco reconstrói pontos, recorrência e certificados atomicamente. Revisão antiga retorna 409; após timeout consulte o registro antes de reenviar.")
    public Detail correct(@PathVariable UUID id,@Valid @RequestBody Correction input,Authentication auth) {
        return service.correct(id,Caller.id(auth),input);
    }
    @PostMapping("/{id}/annulments")
    @Operation(summary="Anular coleta vigente",description="Preserva dados e histórico, gera revisão e reconstrói efeitos pelo banco. Não exclui registro nem cria reembolso. ADMIN ativo, justificativa e revisão esperada. Retorna 200; reenvio com revisão antiga retorna 409.")
    public Detail annul(@PathVariable UUID id,@Valid @RequestBody Annulment input,Authentication auth) {
        return service.annul(id,Caller.id(auth),input);
    }
    @PostMapping("/{id}/restorations")
    @Operation(summary="Restaurar coleta anulada",description="Exclusivo de ADMIN ativo. Exige revisão esperada, justificativa e fatos que voltarão a valer. Revalida motivos ativos e preserva regras de ausência de atendimento. Mantém ciclo e ordem originais; reconstrói efeitos sem criar nova visita.")
    public Detail restore(@PathVariable UUID id,@Valid @RequestBody Correction input,Authentication auth) {
        return service.restore(id,Caller.id(auth),input);
    }
    @GetMapping("/{id}/calculation-revisions")
    @Operation(summary="Consultar revisões de cálculo da coleta",description="Somente ADMIN ativo. Paginação page/size (padrão 0/20, máximo 100); ordem revision DESC. Revisões de cálculo são independentes da revisão operacional e incluem componentes, autoria e justificativa quando existentes. Não expõe snapshots/logs nem representa histórico operacional completo.")
    public History history(@PathVariable UUID id,@Valid @ModelAttribute HistoryQuery query,Authentication auth) {
        return service.history(id,Caller.id(auth),query);
    }
    @GetMapping("/{id}/revisions")
    @Operation(summary="Consultar histórico operacional da coleta",description="Somente ADMIN ativo. Paginação page/size (0/20, máximo 100), revisão operacional decrescente. Inclui autoria, justificativas e motivos vinculados por UUID; currentCode é rótulo atual do catálogo e pode ser null. Página vazia conserva totalElements. Revisões de cálculo têm endpoint próprio. Histórico ausente/incompleto retorna 500, nunca lista vazia. Cada requisição possui snapshot próprio; alterações concorrentes podem deslocar páginas entre requisições.")
    public OperationalHistory revisions(@PathVariable UUID id,@Valid @ModelAttribute HistoryQuery query,Authentication auth) {
        return service.operationalHistory(id,Caller.id(auth),query);
    }
}
