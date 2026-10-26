package br.com.olius.olius_core_api.service;

import br.com.olius.olius_core_api.exception.InvalidWebhookException;
import br.com.olius.olius_core_api.exception.PaymentProviderUnavailableException;
import br.com.olius.olius_core_api.repository.PaymentEventRepository;
import br.com.olius.olius_core_api.security.StripeWebhookSignatureVerifier;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
public class StripeWebhookReceiver {
    private final ObjectProvider<StripeWebhookSignatureVerifier> verifiers;
    private final ObjectMapper json;
    private final PaymentEventRepository events;
    public StripeWebhookReceiver(ObjectProvider<StripeWebhookSignatureVerifier> verifiers,ObjectMapper json,PaymentEventRepository events) {
        this.verifiers=verifiers;this.json=json;this.events=events;
    }
    @Transactional
    public void receive(byte[] body,String signature) {
        var verifier=verifiers.getIfAvailable();
        if(verifier==null) throw new PaymentProviderUnavailableException();
        verifier.verify(body,signature);
        try {
            var event=json.readTree(body);
            if(event==null || !event.path("id").asText().matches("evt_[A-Za-z0-9_]{1,251}")
                    || !event.path("livemode").isBoolean() || event.path("livemode").asBoolean())
                throw new InvalidWebhookException();
            if(!"payment_intent.succeeded".equals(event.path("type").asText())) return;
            var payment=event.path("data").path("object");
            String metadata=payment.path("metadata").path("charge_id").asText("");
            if(metadata.isBlank()) return; // Pagamento do sandbox sem relação com uma cobrança OLIUS.
            UUID.fromString(metadata);
            String paymentId=payment.path("id").asText();
            if(!paymentId.matches("pi_[A-Za-z0-9_]{1,252}") || !"payment_intent".equals(payment.path("object").asText()))
                throw new InvalidWebhookException();
            events.receive(event.path("id").asText(),paymentId);
        } catch(tools.jackson.core.JacksonException | IllegalArgumentException ex) {
            throw new InvalidWebhookException();
        }
    }
}
