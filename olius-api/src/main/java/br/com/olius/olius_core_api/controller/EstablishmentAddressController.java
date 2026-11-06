package br.com.olius.olius_core_api.controller;

import br.com.olius.olius_core_api.dto.AddressReplacement;
import br.com.olius.olius_core_api.dto.IdentityResponses.AddressView;
import br.com.olius.olius_core_api.security.Caller;
import br.com.olius.olius_core_api.service.EstablishmentAddressService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/v1/establishments/me/address")
@PreAuthorize("hasRole('ESTABLISHMENT')")
public class EstablishmentAddressController {
    private final EstablishmentAddressService service;
    public EstablishmentAddressController(EstablishmentAddressService service) { this.service=service; }
    @PutMapping
    @Operation(summary="Substituir o próprio endereço comercial")
    public ResponseEntity<AddressView> replace(@Valid @RequestBody AddressReplacement request,Authentication auth) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.replace(Caller.id(auth),request));
    }
}
