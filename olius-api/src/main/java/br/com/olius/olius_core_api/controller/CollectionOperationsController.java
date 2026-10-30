package br.com.olius.olius_core_api.controller;

import br.com.olius.olius_core_api.dto.CollectionContracts.*;
import br.com.olius.olius_core_api.dto.CollectionContracts.Record;
import br.com.olius.olius_core_api.dto.CollectionRequestContracts;
import br.com.olius.olius_core_api.service.CollectionOperationsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/v1/operations/b2b")
@Tag(name="Operação de coleta B2B",description="Motorista sem login: cada ação exige CPF, CNH e QR atual. Identificação fraca do MVP; não comprova presença física.")
public class CollectionOperationsController {
    private final CollectionOperationsService service;
    public CollectionOperationsController(CollectionOperationsService service) { this.service=service; }
    @PostMapping("/context")
    @Operation(summary="Validar identificação e consultar pedidos aptos",description="Recebe cpf, cnh e qrToken no JSON, sem colocar documentos na URL. Retorna somente pedidos aprovados/agendados e sem coleta do estabelecimento identificado, com paginação e motivos ativos do fluxo normal. Não registra chegada por abrir o formulário.")
    public Context context(@Valid @RequestBody DriverAccess access,@Valid @ModelAttribute CollectionRequestContracts.Pagination query) {
        return service.context(access,query.page(),query.size());
    }
    @PostMapping("/requests/{id}/arrival")
    @Operation(summary="Registrar chegada",description="Revalida motorista ativo e QR. Ação explícita, horário do servidor. Reenvio do mesmo motorista retorna a chegada original; outro motorista conflita. Não cria coleta nem pontos.")
    public CollectionRequestContracts.RequestView arrival(@PathVariable UUID id,@Valid @RequestBody DriverAccess access) {
        return service.arrival(id,access);
    }
    @PostMapping("/requests/{id}/collection")
    @Operation(summary="Registrar resultado da visita",description="Corpo contém access (cpf,cnh,qrToken), volumes, condição, ocorrência, lista de motivos e observação. Exige chegada do mesmo motorista e aceite do estabelecimento. Data, resultado, pontuação e revisão são do servidor. Solicitação é a chave de idempotência: reenvio equivalente preserva data e não duplica efeitos; divergente retorna 409. Retorna 200 também no primeiro registro. Falta de óleo/inadequação/ocorrência exige observação. Não aceita motivos administrativos.")
    public Detail record(@PathVariable UUID id,@Valid @RequestBody Record request) { return service.record(id,request); }
}
