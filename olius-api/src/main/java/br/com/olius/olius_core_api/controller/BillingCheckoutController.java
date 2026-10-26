package br.com.olius.olius_core_api.controller;

import br.com.olius.olius_core_api.dto.CheckoutResponse;
import br.com.olius.olius_core_api.dto.InitialCheckoutRequest;
import br.com.olius.olius_core_api.security.Caller;
import br.com.olius.olius_core_api.service.InitialCheckoutService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/billing/initial-checkouts")
@Tag(name="Cobranças",description="Contratação, renovação e upgrade pelo estabelecimento autenticado")
public class BillingCheckoutController {
    private final InitialCheckoutService checkout;
    public BillingCheckoutController(InitialCheckoutService checkout) { this.checkout=checkout; }
    @PostMapping(produces="application/json")
    @Operation(summary="Obter Checkout para contratação inicial")
    public CheckoutResponse create(@Valid @RequestBody InitialCheckoutRequest request,
            @RequestHeader("Idempotency-Key") UUID key,Authentication authentication) {
        return checkout.create(Caller.id(authentication),request.planId(),key);
    }
}
