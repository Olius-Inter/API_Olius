package br.com.olius.olius_core_api.controller;

import br.com.olius.olius_core_api.dto.CollectionContracts.*;
import br.com.olius.olius_core_api.dto.CollectionRequestContracts;
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

@RestController @PreAuthorize("hasRole('ESTABLISHMENT')") @Tag(name="Coletas do estabelecimento")
public class CollectionController {
    private final CollectionOperationsService operations;
    private final CollectionReadService reads;
    public CollectionController(CollectionOperationsService operations,CollectionReadService reads) { this.operations=operations;this.reads=reads; }
    @PostMapping("/api/v1/collection-requests/{id}/service-acceptance")
    @Operation(summary="Confirmar aceite do atendimento",description="O próprio estabelecimento autenticado confirma após a chegada. Horário definido no banco. Reenvio retorna o aceite original. Bloqueia cancelamento posterior; motorista não pode aceitar em nome do estabelecimento.")
    public CollectionRequestContracts.RequestView accept(@PathVariable UUID id,Authentication auth) { return operations.accept(id,Caller.id(auth)); }
    @GetMapping("/api/v1/collections")
    @Operation(summary="Listar minhas coletas",description="Somente titular; filtros result, recordStatus, período físico [from,until), page/size até 100. Ordem collectionDate DESC,id DESC. Sem filtro de status inclui anuladas, identificadas explicitamente.")
    public Page list(@Valid @ModelAttribute Query query,Authentication auth) { return reads.list(Caller.id(auth),false,null,null,query); }
    @GetMapping("/api/v1/collections/{id}")
    @Operation(summary="Consultar minha coleta e cálculo vigente",description="404 para coleta de outro titular. Componentes pertencem exclusivamente ao cálculo is_current; nominal de -50 pode reduzir menos de 50 no saldo por causa do piso zero.")
    public Detail detail(@PathVariable UUID id,Authentication auth) { return reads.detail(id,Caller.id(auth),false); }
    @GetMapping("/api/v1/establishments/me/score")
    @Operation(summary="Consultar saldo geral e progresso operacional",description="Saldo geral oficial, sucessos acumulados e litros efetivamente coletados em registros vigentes, inclusive malsucedidos. Não é ranking anual; ranking pertence à API Redis.")
    public Score score(Authentication auth) { return reads.score(Caller.id(auth)); }
}
