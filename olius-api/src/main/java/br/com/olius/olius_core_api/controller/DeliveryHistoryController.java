package br.com.olius.olius_core_api.controller;

import br.com.olius.olius_core_api.dto.DeliveryHistoryContracts.Page;
import br.com.olius.olius_core_api.security.Caller;
import br.com.olius.olius_core_api.service.DeliveryHistoryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/users/me/deliveries")
@PreAuthorize("hasRole('CITIZENS')")
public class DeliveryHistoryController {
    private final DeliveryHistoryService service;

    public DeliveryHistoryController(DeliveryHistoryService service) { this.service=service; }

    @GetMapping
    @Operation(summary="Consultar histórico próprio de entregas B2C")
    public Page own(@Parameter(schema=@Schema(type="integer",format="int32",defaultValue="0",minimum="0",maximum="2147483647"))
                    @RequestParam(required=false) String page,
                    @Parameter(schema=@Schema(type="integer",format="int32",defaultValue="20",minimum="1",maximum="100"))
                    @RequestParam(required=false) String size,
                    Authentication authentication) {
        return service.own(Caller.id(authentication),PevPagination.page(page),PevPagination.size(size));
    }
}
