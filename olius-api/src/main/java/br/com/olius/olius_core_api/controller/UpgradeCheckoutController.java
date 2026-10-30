package br.com.olius.olius_core_api.controller;

import br.com.olius.olius_core_api.dto.UpgradeCheckoutRequest;
import br.com.olius.olius_core_api.dto.UpgradeCheckoutResponse;
import br.com.olius.olius_core_api.security.Caller;
import br.com.olius.olius_core_api.service.UpgradeCheckoutService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/billing/upgrade-checkouts")
@Tag(name="Cobranças")
public class UpgradeCheckoutController {
    private final UpgradeCheckoutService checkout;
    public UpgradeCheckoutController(UpgradeCheckoutService checkout) { this.checkout=checkout; }
    @PostMapping(produces="application/json")
    @Operation(summary="Obter Checkout de upgrade do ciclo atual")
    public UpgradeCheckoutResponse create(@Valid @RequestBody UpgradeCheckoutRequest request,
            @RequestHeader("Idempotency-Key") UUID key,Authentication authentication) {
        return checkout.create(Caller.id(authentication),request.planId(),key);
    }
}
