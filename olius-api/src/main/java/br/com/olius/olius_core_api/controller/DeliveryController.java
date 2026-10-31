package br.com.olius.olius_core_api.controller;

import br.com.olius.olius_core_api.dto.DeliveryContracts.Record;
import br.com.olius.olius_core_api.dto.DeliveryContracts.View;
import br.com.olius.olius_core_api.dto.DeliveryContracts.PevPage;
import br.com.olius.olius_core_api.security.Caller;
import br.com.olius.olius_core_api.service.DeliveryService;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/pevs/{pevId}/deliveries")
public class DeliveryController {
    private final DeliveryService service;

    public DeliveryController(DeliveryService service) { this.service=service; }

    @PostMapping
    public ResponseEntity<View> record(@PathVariable UUID pevId,
                                       @Valid @RequestBody Record request,
                                       Authentication authentication) {
        var result=service.register(pevId,Caller.id(authentication),Caller.type(authentication),request);
        if(result.created())
            return ResponseEntity.created(URI.create("/api/v1/pevs/"+pevId+"/deliveries/"+result.delivery().id()))
                .body(result.delivery());
        return ResponseEntity.ok(result.delivery());
    }

    @GetMapping
    public PevPage list(@PathVariable UUID pevId,@RequestParam(required=false) String page,
                        @RequestParam(required=false) String size,Authentication authentication) {
        return service.listForPev(pevId,Caller.id(authentication),Caller.type(authentication),
            PevPagination.page(page),PevPagination.size(size));
    }

    @GetMapping("/{deliveryId}")
    public View detail(@PathVariable UUID pevId,@PathVariable UUID deliveryId,
                       Authentication authentication) {
        return service.detail(pevId,deliveryId,Caller.id(authentication),Caller.type(authentication));
    }
}
