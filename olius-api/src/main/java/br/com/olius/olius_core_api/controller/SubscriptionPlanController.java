package br.com.olius.olius_core_api.controller;

import br.com.olius.olius_core_api.dto.SubscriptionPlanResponse;
import br.com.olius.olius_core_api.service.SubscriptionPlanCatalogService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/subscription-plans")
@Tag(name="Planos",description="Consulta pública das ofertas ativas do OLIUS")
public class SubscriptionPlanController {
    private final SubscriptionPlanCatalogService catalog;
    public SubscriptionPlanController(SubscriptionPlanCatalogService catalog) { this.catalog=catalog; }

    @GetMapping(produces = "application/json")
    @Operation(summary="Consultar planos ativos")
    public List<SubscriptionPlanResponse> activePlans() { return catalog.activePlans(); }
}
