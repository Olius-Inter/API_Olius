package br.com.olius.olius_core_api.integration.payment;

import java.time.Clock;
import java.time.Duration;
import java.util.Locale;
import org.springframework.http.MediaType;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;

/** HTTP financeiro fora das transações; erros externos nunca são propagados com corpo/causa. */
public final class StripeRefundGateway implements RefundGateway {
    private final RestClient client;
    private final Clock clock;
    public StripeRefundGateway(String secretKey) {
        this(StripeCheckoutGateway.client(secretKey),Clock.systemUTC());
        if(secretKey==null || !secretKey.startsWith("sk_test_"))
            throw new IllegalArgumentException("Reembolso exige sandbox");
    }
    StripeRefundGateway(RestClient client,Clock clock) { this.client=client;this.clock=clock; }
    @Override public Result create(Request request) { return execute(request,null); }
    @Override public Result retrieve(Request request,String providerId) {
        if(providerId==null || !providerId.matches("re_[A-Za-z0-9_]+")) throw review();
        return execute(request,providerId);
    }
    private Result execute(Request request,String providerId) {
        if(TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Reembolso deve ser chamado fora da transação");
        long cents=validate(request);
        try {
            var payment=client.get().uri("/v1/payment_intents/{id}",request.paymentId()).retrieve().body(JsonNode.class);
            if(payment==null || !request.paymentId().equals(payment.path("id").asText())
                    || !"payment_intent".equals(payment.path("object").asText())
                    || payment.path("livemode").asBoolean(true)
                    || !"succeeded".equals(payment.path("status").asText())
                    || !"brl".equals(payment.path("currency").asText())
                    || !payment.path("amount_received").isIntegralNumber()
                    || payment.path("amount_received").asLong(-1)!=cents
                    || !request.chargeId().toString().equals(payment.path("metadata").path("charge_id").asText()))
                throw review();
            org.springframework.http.ResponseEntity<JsonNode> response;
            if(providerId==null) {
                // Margem técnica para não iniciar HTTP junto da fronteira de retenção de 24h.
                if(!clock.instant().plusSeconds(30).isBefore(request.firstAttemptAt().plus(Duration.ofHours(24))))
                    throw new Failure(FailureKind.RECONCILIATION_REQUIRED);
                var form=new LinkedMultiValueMap<String,String>();
                form.add("payment_intent",request.paymentId());
                form.add("amount",Long.toString(cents));
                form.add("metadata[refund_id]",request.refundId().toString());
                response=client.post().uri("/v1/refunds").header("Idempotency-Key",request.idempotencyKey().toString())
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED).body(form).retrieve().toEntity(JsonNode.class);
            } else {
                response=client.get().uri("/v1/refunds/{id}",providerId).retrieve().toEntity(JsonNode.class);
            }
            JsonNode result=response.getBody();
            if(result==null || !result.path("id").asText().matches("re_[A-Za-z0-9_]+")
                    || (providerId!=null && !providerId.equals(result.path("id").asText()))
                    || !"refund".equals(result.path("object").asText())
                    || !request.paymentId().equals(result.path("payment_intent").asText())
                    || !"brl".equals(result.path("currency").asText())
                    || !result.path("amount").isIntegralNumber() || result.path("amount").asLong(-1)!=cents
                    || !request.refundId().toString().equals(result.path("metadata").path("refund_id").asText()))
                throw review();
            Status status=Status.valueOf(result.path("status").asText().toUpperCase(Locale.ROOT));
            String requestId=response.getHeaders().getFirst("Request-Id");
            if(requestId!=null && (!requestId.matches("req_[A-Za-z0-9_]+") || requestId.length()>255)) requestId=null;
            return new Result(result.path("id").asText(),status,requestId);
        } catch(RestClientResponseException ex) {
            int status=ex.getStatusCode().value();
            throw new Failure(status==409 || status==429 || status>=500 ? FailureKind.TEMPORARY : FailureKind.REVIEW_REQUIRED);
        } catch(RestClientException ex) {
            throw new Failure(FailureKind.TEMPORARY);
        } catch(IllegalArgumentException | ArithmeticException ex) {
            throw review();
        }
    }
    private long validate(Request request) {
        if(request==null || request.refundId()==null || request.idempotencyKey()==null || request.chargeId()==null
                || request.paymentId()==null || !request.paymentId().matches("pi_[A-Za-z0-9_]+")
                || request.amount()==null || request.amount().signum()<=0 || request.firstAttemptAt()==null
                || request.firstAttemptAt().isAfter(clock.instant())) throw review();
        try { return request.amount().movePointRight(2).longValueExact(); }
        catch(ArithmeticException ex) { throw review(); }
    }
    private Failure review() { return new Failure(FailureKind.REVIEW_REQUIRED); }
}
