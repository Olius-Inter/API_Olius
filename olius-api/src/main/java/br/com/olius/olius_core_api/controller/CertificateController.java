package br.com.olius.olius_core_api.controller;

import br.com.olius.olius_core_api.dto.CertificateContracts.*;
import br.com.olius.olius_core_api.security.Caller;
import br.com.olius.olius_core_api.service.CertificateReadService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController @Tag(name="Certificados ambientais")
public class CertificateController {
    private final CertificateReadService reads;
    public CertificateController(CertificateReadService reads) { this.reads=reads; }
    @GetMapping("/api/v1/certificate-levels") @PreAuthorize("hasAnyRole('ESTABLISHMENT','ADMIN')")
    @Operation(summary="Consultar níveis ambientais",description="Catálogo atual ordenado por litros, nome e ID. Sem níveis retorna lista vazia. Não permite modificar metas.")
    public List<Level> levels(Authentication auth) { return reads.levels(Caller.id(auth),isAdmin(auth)); }
    @GetMapping("/api/v1/certificates") @PreAuthorize("hasRole('ESTABLISHMENT')")
    @Operation(summary="Listar meus certificados",description="Titular ativo, independentemente da assinatura. Filtros status, levelId, emissão [issuedFrom,issuedUntil), page/size até 100. Ordem issuedAt DESC,id DESC. Inclui revogados quando sem filtro.")
    public Page list(@Valid @ModelAttribute Query query,Authentication auth) { return reads.list(Caller.id(auth),false,null,query); }
    @GetMapping("/api/v1/certificates/{id}") @PreAuthorize("hasAnyRole('ESTABLISHMENT','ADMIN')")
    @Operation(summary="Consultar certificado",description="Somente titular ou ADMIN ativo; outro titular recebe 404. Estado atual e metadados atuais do nível, sem PDF ou histórico completo. Reativação preserva identidade; status define validade.")
    public View detail(@PathVariable UUID id,Authentication auth) { return reads.detail(id,Caller.id(auth),isAdmin(auth)); }
    @GetMapping("/api/v1/establishments/me/certificate-progress") @PreAuthorize("hasRole('ESTABLISHMENT')")
    @Operation(summary="Consultar meu progresso ambiental",description="Litros reais de coletas RECORDED, inclusive malsucedidas, acumulados sem corte anual. Sem B2C, reservas ou perdas. GET nunca emite certificados. Meta alcançada pode ainda não ter certificado quando catálogo foi carregado depois das coletas.")
    public Progress progress(Authentication auth) { return reads.progress(Caller.id(auth),false,null); }
    @GetMapping("/api/v1/admin/certificates") @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary="Consultar certificados administrativamente",description="ADMIN ativo. Filtro opcional establishmentId além dos filtros de emissão, nível e estado. Consulta atual paginada, sem alterações ou histórico de auditoria.")
    public Page administrativeList(@RequestParam(required=false) UUID establishmentId,@Valid @ModelAttribute Query query,Authentication auth) {
        return reads.list(Caller.id(auth),true,establishmentId,query);
    }
    @GetMapping("/api/v1/admin/establishments/{id}/certificate-progress") @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary="Consultar progresso de estabelecimento",description="ADMIN ativo; 404 para estabelecimento inexistente. Mesma regra ambiental do titular, sem alterar ou reconciliar dados.")
    public Progress administrativeProgress(@PathVariable UUID id,Authentication auth) { return reads.progress(Caller.id(auth),true,id); }
    private boolean isAdmin(Authentication auth) { return auth.getAuthorities().stream().anyMatch(a->a.getAuthority().equals("ROLE_ADMIN")); }
}
