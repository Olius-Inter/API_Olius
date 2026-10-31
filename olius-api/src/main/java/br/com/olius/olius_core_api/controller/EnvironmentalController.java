package br.com.olius.olius_core_api.controller;

import br.com.olius.olius_core_api.dto.CollectionContracts.Period;
import br.com.olius.olius_core_api.dto.EnvironmentalContracts.Summary;
import br.com.olius.olius_core_api.security.Caller;
import br.com.olius.olius_core_api.service.EnvironmentalReadService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController @Tag(name="Consultas ambientais")
public class EnvironmentalController {
    private final EnvironmentalReadService reads;
    public EnvironmentalController(EnvironmentalReadService reads) { this.reads=reads; }
    @GetMapping("/api/v1/establishments/me/environmental-summary") @PreAuthorize("hasRole('ESTABLISHMENT')")
    @Operation(summary="Consultar minhas operações por período",description="Estabelecimento ativo, sem exigir assinatura vigente. Período físico [from,until), somente coletas RECORDED. Inclui litros reais malsucedidos; exclui anuladas, reservas e perdas. deliveries é null: entregas dos cidadãos não compõem o volume B2B. Sem emissão ou arquivo comercial.")
    public Summary own(@Valid @ModelAttribute Period period,Authentication auth) { return reads.summary(Caller.id(auth),false,period); }
    @GetMapping("/api/v1/admin/environmental-summary") @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary="Consultar indicadores operacionais",description="ADMIN ativo. Período físico [from,until), registros RECORDED. Separa coletas B2B e entregas B2C; os volumes não devem ser somados como volume ambiental único. Sem conversões ambientais estimadas, PDF ou documento comercial.")
    public Summary administrative(@Valid @ModelAttribute Period period,Authentication auth) { return reads.summary(Caller.id(auth),true,period); }
}
