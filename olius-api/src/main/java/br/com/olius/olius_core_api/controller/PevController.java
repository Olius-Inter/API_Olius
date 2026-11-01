package br.com.olius.olius_core_api.controller;

import br.com.olius.olius_core_api.dto.PevRequests.Create;
import br.com.olius.olius_core_api.dto.PevRequests.UpdateAddress;
import br.com.olius.olius_core_api.dto.PevResponses.OwnPev;
import br.com.olius.olius_core_api.dto.PevResponses.PublicPage;
import br.com.olius.olius_core_api.security.Caller;
import br.com.olius.olius_core_api.service.PevService;
import jakarta.validation.Valid;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/pevs")
public class PevController {
    private final PevService service;

    public PevController(PevService service) { this.service=service; }

    @PostMapping
    public ResponseEntity<OwnPev> request(@Valid @RequestBody Create request, Authentication authentication) {
        var result=service.request(request,Caller.id(authentication),Caller.type(authentication));
        return ResponseEntity.created(URI.create("/api/v1/pevs/me")).body(result);
    }

    @GetMapping("/me")
    public OwnPev own(Authentication authentication) {
        return service.own(Caller.id(authentication),Caller.type(authentication));
    }

    @PatchMapping("/me/address")
    public OwnPev updateAddress(@Valid @RequestBody UpdateAddress request,Authentication authentication) {
        return service.updateAddress(request,Caller.id(authentication),Caller.type(authentication));
    }

    @GetMapping
    public PublicPage approved(@RequestParam(required=false) String page,
                               @RequestParam(required=false) String size) {
        return service.approved(PevPagination.page(page),PevPagination.size(size));
    }
}
