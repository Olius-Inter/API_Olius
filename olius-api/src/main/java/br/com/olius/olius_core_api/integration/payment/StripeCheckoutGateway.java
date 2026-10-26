package br.com.olius.olius_core_api.integration.payment;

import br.com.olius.olius_core_api.exception.PaymentProviderUnavailableException;
import java.net.URI;
import java.net.HttpURLConnection;
import java.io.IOException;
import java.time.Instant;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;

/** Adaptador HTTP isolado da persistência e da concessão de benefícios. */
public final class StripeCheckoutGateway implements CheckoutGateway, PaymentVerificationGateway {
    static final String API_VERSION="2026-09-30.endive";
    private final RestClient client;
    private final URI successUrl;
    private final URI cancelUrl;

    public StripeCheckoutGateway(String secretKey, URI successUrl, URI cancelUrl) {
        this(client(secretKey),successUrl,cancelUrl);
    }

    StripeCheckoutGateway(RestClient client, URI successUrl, URI cancelUrl) {
        this.client=client;
        this.successUrl=successUrl;
        this.cancelUrl=cancelUrl;
    }

    private static RestClient client(String secretKey) {
        var factory=new NoRedirectRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(15000);
        return RestClient.builder().baseUrl("https://api.stripe.com")
                .requestFactory(factory).defaultHeader("Authorization","Bearer "+secretKey)
                .defaultHeader("Stripe-Version",API_VERSION).build();
    }

    /** Evita dependência do seletor NIO local e não encaminha credenciais em redirecionamentos. */
    private static final class NoRedirectRequestFactory extends SimpleClientHttpRequestFactory {
        @Override
        protected void prepareConnection(HttpURLConnection connection,String method) throws IOException {
            super.prepareConnection(connection,method);
            connection.setInstanceFollowRedirects(false);
        }
    }

    @Override
    public CheckoutSession create(CheckoutCharge charge) {
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Checkout deve ser chamado fora da transação do banco");
        if (charge==null || charge.chargeId()==null || charge.idempotencyKey()==null
                || charge.planName()==null || charge.planName().isBlank()
                || charge.amount()==null || charge.amount().signum()<=0 || charge.expiresAt()==null)
            throw new IllegalArgumentException("Cobrança incompleta");
        // Falha se houver fração de centavo; nunca arredonda o preço comercial.
        long cents=charge.amount().movePointRight(2).longValueExact();
        var form=new LinkedMultiValueMap<String,String>();
        form.add("mode","payment");
        form.add("allowed_payment_method_types[0]","card");
        form.add("success_url",successUrl.toString());
        form.add("cancel_url",cancelUrl.toString());
        form.add("client_reference_id",charge.chargeId().toString());
        form.add("metadata[charge_id]",charge.chargeId().toString());
        form.add("payment_intent_data[metadata][charge_id]",charge.chargeId().toString());
        form.add("line_items[0][price_data][currency]","brl");
        form.add("line_items[0][price_data][unit_amount]",Long.toString(cents));
        form.add("line_items[0][price_data][product_data][name]",charge.planName());
        form.add("line_items[0][quantity]","1");
        form.add("expires_at",Long.toString(charge.expiresAt().getEpochSecond()));
        try {
            JsonNode result=client.post().uri("/v1/checkout/sessions")
                    .header("Idempotency-Key",charge.idempotencyKey().toString())
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED).body(form)
                    .retrieve().body(JsonNode.class);
            return verifiedSession(result,charge,cents);
        } catch (RestClientException | IllegalArgumentException ex) {
            // Não anexar a causa: pode conter dados do cliente ou corpo externo.
            throw new PaymentProviderUnavailableException();
        }
    }

    @Override
    public CheckoutSession retrieve(CheckoutCharge charge,String providerId) {
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Consulta ao provedor deve ocorrer fora da transação");
        if(providerId==null || !providerId.matches("cs_test_[A-Za-z0-9_]+"))
            throw new IllegalArgumentException("Identidade Stripe inválida");
        try {
            JsonNode result=client.get().uri("/v1/checkout/sessions/{id}",providerId)
                    .retrieve().body(JsonNode.class);
            var session=verifiedSession(result,charge,charge.amount().movePointRight(2).longValueExact());
            if(!providerId.equals(session.providerId())) throw new PaymentProviderUnavailableException();
            return session;
        } catch(RestClientException | IllegalArgumentException ex) {
            throw new PaymentProviderUnavailableException();
        }
    }

    @Override
    public VerifiedPayment verify(String eventId,String paymentId) {
        if(TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Verificação financeira deve ocorrer fora da transação");
        if(eventId==null || !eventId.matches("evt_[A-Za-z0-9_]+")
                || paymentId==null || !paymentId.matches("pi_[A-Za-z0-9_]+"))
            throw new PaymentProviderUnavailableException();
        try {
            var event=client.get().uri("/v1/events/{id}",eventId).retrieve().body(JsonNode.class);
            var payment=client.get().uri("/v1/payment_intents/{id}",paymentId).retrieve().body(JsonNode.class);
            if(event==null || payment==null || !eventId.equals(event.path("id").asText())
                    || !"event".equals(event.path("object").asText())
                    || !"payment_intent.succeeded".equals(event.path("type").asText())
                    || event.path("livemode").asBoolean(true)
                    || !paymentId.equals(event.path("data").path("object").path("id").asText())
                    || !paymentId.equals(payment.path("id").asText())
                    || !"payment_intent".equals(payment.path("object").asText())
                    || !"succeeded".equals(payment.path("status").asText())
                    || payment.path("livemode").asBoolean(true)
                    || !"brl".equals(payment.path("currency").asText())
                    || !payment.path("amount_received").isIntegralNumber()
                    || payment.path("amount_received").asLong(-1)<=0
                    || event.path("created").asLong(-1)<=0)
                throw new PaymentProviderUnavailableException();
            String chargeId=payment.path("metadata").path("charge_id").asText();
            if(!chargeId.equals(event.path("data").path("object").path("metadata").path("charge_id").asText()))
                throw new PaymentProviderUnavailableException();
            return new VerifiedPayment(paymentId,java.util.UUID.fromString(chargeId),
                    java.math.BigDecimal.valueOf(payment.path("amount_received").asLong(),2),"BRL",
                    Instant.ofEpochSecond(event.path("created").asLong()));
        } catch(RestClientException | IllegalArgumentException | java.time.DateTimeException ex) {
            throw new PaymentProviderUnavailableException();
        }
    }

    private CheckoutSession verifiedSession(JsonNode result, CheckoutCharge charge, long cents) {
        if (result==null || !result.path("id").asText().startsWith("cs_test_")
                || !"payment".equals(result.path("mode").asText())
                || !"open".equals(result.path("status").asText())
                || result.path("livemode").asBoolean(true)
                || !"brl".equals(result.path("currency").asText())
                || result.path("amount_total").asLong(-1)!=cents
                || !charge.chargeId().toString().equals(result.path("client_reference_id").asText())
                || result.path("expires_at").asLong(-1)!=charge.expiresAt().getEpochSecond())
            throw new PaymentProviderUnavailableException();
        URI url=URI.create(result.path("url").asText());
        if (!"https".equals(url.getScheme()) || !"checkout.stripe.com".equals(url.getHost())
                || url.getUserInfo()!=null || (url.getPort()!=-1 && url.getPort()!=443))
            throw new PaymentProviderUnavailableException();
        return new CheckoutSession(result.path("id").asText(),url,
                Instant.ofEpochSecond(result.path("expires_at").asLong()));
    }
}
