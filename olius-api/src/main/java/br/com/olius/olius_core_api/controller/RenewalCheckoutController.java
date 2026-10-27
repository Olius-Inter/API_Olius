package br.com.olius.olius_core_api.controller;

import br.com.olius.olius_core_api.dto.CheckoutResponse;
import br.com.olius.olius_core_api.dto.RenewalCheckoutRequest;
import br.com.olius.olius_core_api.security.Caller;
import br.com.olius.olius_core_api.service.RenewalCheckoutService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/billing/renewal-checkouts")
@Tag(name="Cobranças")
public class RenewalCheckoutController {
    private final RenewalCheckoutService checkout;
    public RenewalCheckoutController(RenewalCheckoutService checkout) { this.checkout=checkout; }
    @PostMapping(produces="application/json")
    @Operation(summary="Obter Checkout para renovação manual")
    public CheckoutResponse create(@Valid @RequestBody RenewalCheckoutRequest request,
            @RequestHeader("Idempotency-Key") UUID key,Authentication authentication) {
        return checkout.create(Caller.id(authentication),request.planId(),key);
    }
}
