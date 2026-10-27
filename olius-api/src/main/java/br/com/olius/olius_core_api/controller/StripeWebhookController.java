package br.com.olius.olius_core_api.controller;

import br.com.olius.olius_core_api.exception.InvalidWebhookException;
import br.com.olius.olius_core_api.service.StripeWebhookReceiver;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/webhooks/stripe")
@Tag(name="Webhooks")
public class StripeWebhookController {
    private static final int MAX_BODY=262144;
    private final StripeWebhookReceiver receiver;
    public StripeWebhookController(StripeWebhookReceiver receiver) { this.receiver=receiver; }
    @PostMapping(consumes="application/json")
    @Operation(summary="Receber evento Stripe assinado")
    public ResponseEntity<Void> receive(HttpServletRequest request,
            @RequestHeader(value="Stripe-Signature",required=false) String signature) throws IOException {
        byte[] raw=request.getInputStream().readNBytes(MAX_BODY+1);
        if(raw.length>MAX_BODY) throw new InvalidWebhookException();
        receiver.receive(raw,signature);
        return ResponseEntity.noContent().build();
    }
}
