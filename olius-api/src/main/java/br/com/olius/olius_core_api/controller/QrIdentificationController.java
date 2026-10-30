package br.com.olius.olius_core_api.controller;

import br.com.olius.olius_core_api.security.Caller;
import br.com.olius.olius_core_api.service.QrIdentificationService;
import br.com.olius.olius_core_api.service.QrIdentificationService.IdentifiedCitizen;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/qr/resolutions")
public class QrIdentificationController {
    private final QrIdentificationService service;

    public QrIdentificationController(QrIdentificationService service) {
        this.service = service;
    }

    @PostMapping
    public IdentifiedCitizen identify(@Valid @RequestBody IdentificationRequest request,
                                      Authentication authentication) {
        return service.identify(request.qrToken(), Caller.id(authentication), Caller.type(authentication));
    }

    public record IdentificationRequest(@NotBlank String qrToken) { }
}
