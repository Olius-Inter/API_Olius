package br.com.olius.olius_core_api.controller;

import br.com.olius.olius_core_api.dto.DriverContracts.*;
import br.com.olius.olius_core_api.security.Caller;
import br.com.olius.olius_core_api.service.DriverService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/v1/admin/drivers") @PreAuthorize("hasRole('ADMIN')")
@Tag(name="Motoristas",description="Cadastro operacional restrito a ADMIN ativo; motorista não possui login")
public class DriverAdministrationController {
    private final DriverService service;
    public DriverAdministrationController(DriverService service) { this.service=service; }
    @PostMapping
    @Operation(summary="Cadastrar motorista",description="Nome, CPF e CNH sem máscara. CPF/CNH têm 11 dígitos e unicidade; isso não comprova validade perante órgãos oficiais. ID, estado inicial ACTIVE e datas são controlados pelo servidor. Não cria usuário, senha ou login.")
    public ResponseEntity<View> create(@Valid @RequestBody Create request,Authentication auth) {
        var result=service.create(Caller.id(auth),request);
        return ResponseEntity.created(URI.create("/api/v1/admin/drivers/"+result.id())).body(result);
    }
    @GetMapping
    @Operation(summary="Listar motoristas",description="Consulta administrativa paginada, até 100 registros, por nome e ID; filtro opcional por status. Contém documentos pessoais e exige ADMIN ativo.")
    public Page list(@Valid @ModelAttribute Query query,Authentication auth) { return service.list(Caller.id(auth),query); }
    @GetMapping("/{id}")
    @Operation(summary="Consultar motorista",description="Detalhe cadastral restrito ao ADMIN; inclui motoristas inativos para preservar referências operacionais.")
    public View detail(@PathVariable UUID id,Authentication auth) { return service.detail(Caller.id(auth),id); }
    @PatchMapping("/{id}")
    @Operation(summary="Atualizar cadastro",description="Atualiza somente nome, CPF e/ou CNH; ao menos um campo é obrigatório. Não altera status, datas ou coletas anteriores.")
    public View update(@PathVariable UUID id,@Valid @RequestBody Update request,Authentication auth) {
        return service.update(Caller.id(auth),id,request);
    }
    @PatchMapping("/{id}/status")
    @Operation(summary="Ativar ou inativar motorista",description="Preserva o cadastro e seus vínculos. Não representa disponibilidade por rota nem cria conta de acesso. Não existe DELETE físico neste módulo.")
    public View status(@PathVariable UUID id,@Valid @RequestBody StatusChange request,Authentication auth) {
        return service.status(Caller.id(auth),id,request);
    }
}
