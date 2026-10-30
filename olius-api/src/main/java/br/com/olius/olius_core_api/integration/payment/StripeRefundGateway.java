package br.com.olius.olius_core_api.integration.payment;

import java.time.Clock;
import java.time.Duration;
import java.util.Locale;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;

/** Verifica a origem financeira e cria/consulta devoluções integrais, sempre fora da transação SQL. */
public final class StripeRefundGateway implements RefundGateway {
    private static final String FIELD_PAYMENT_INTENT="payment_intent";
    private static final String FIELD_AMOUNT="amount";
    private static final Duration MAX_CLOCK_SKEW=Duration.ofMinutes(5);
    private static final Duration CREATION_WINDOW=Duration.ofHours(24);
    private final RestClient client;
    private final Clock clock;
    public StripeRefundGateway(String secretKey) {
        this(StripeCheckoutGateway.client(secretKey),Clock.systemUTC());
        if(secretKey==null || !secretKey.startsWith("sk_test_")) throw new IllegalArgumentException("Reembolso exige sandbox");
    }
    StripeRefundGateway(RestClient client,Clock clock) { this.client=client;this.clock=clock; }

    /** Reusa a chave persistida; o pedido só pode existir se já foi autorizado pelo banco. */
    @Override public Result create(Request request) { return execute(request,null); }
    /** Consulta o mesmo reembolso, inclusive depois da janela de criação; nunca emite outro POST. */
    @Override public Result retrieve(Request request,String providerId) {
        if(providerId==null || !providerId.matches("re_\\w+")) throw review();
        return execute(request,providerId);
    }
    private Result execute(Request request,String providerId) {
        if(TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Reembolso deve ser chamado fora da transação");
        long cents=validateRequest(request);
        try {
            verifyParentPayment(request,cents);
            var response=providerId==null ? createRefund(request,cents)
                : client.get().uri("/v1/refunds/{id}",providerId).retrieve().toEntity(JsonNode.class);
            return verifiedResult(request,providerId,cents,response);
        } catch(RestClientResponseException ex) {
            int status=ex.getStatusCode().value();
            throw new RefundFailure(status==409 || status==429 || status>=500 ? FailureKind.TEMPORARY : FailureKind.REVIEW_REQUIRED);
        } catch(RestClientException ex) {
            throw new RefundFailure(FailureKind.TEMPORARY);
        } catch(IllegalArgumentException | ArithmeticException ex) {
            throw review();
        }
    }
    /** Confere ambiente, vínculo, valor e pagamento concluído antes de qualquer operação de devolução. */
    private void verifyParentPayment(Request request,long cents) {
        var payment=client.get().uri("/v1/payment_intents/{id}",request.paymentId()).retrieve().body(JsonNode.class);
        if(payment==null || !request.paymentId().equals(payment.path("id").asText())
                || !FIELD_PAYMENT_INTENT.equals(payment.path("object").asText()) || payment.path("livemode").asBoolean(true)
                || !"succeeded".equals(payment.path("status").asText()) || !"brl".equals(payment.path("currency").asText())
                || !payment.path("amount_received").isIntegralNumber() || payment.path("amount_received").asLong(-1)!=cents
                || !request.chargeId().toString().equals(payment.path("metadata").path("charge_id").asText())) throw review();
    }
    /** Compensa o desvio admitido conservadoramente: tolerar relógios não pode estender a janela de 24h. */
    private ResponseEntity<JsonNode> createRefund(Request request,long cents) {
        var deadline=request.firstAttemptAt().plus(CREATION_WINDOW).minus(MAX_CLOCK_SKEW);
        if(!clock.instant().plusSeconds(30).isBefore(deadline)) throw new RefundFailure(FailureKind.RECONCILIATION_REQUIRED);
        var form=new LinkedMultiValueMap<String,String>();
        form.add(FIELD_PAYMENT_INTENT,request.paymentId());form.add(FIELD_AMOUNT,Long.toString(cents));
        form.add("metadata[refund_id]",request.refundId().toString());
        return client.post().uri("/v1/refunds").header("Idempotency-Key",request.idempotencyKey().toString())
            .contentType(MediaType.APPLICATION_FORM_URLENCODED).body(form).retrieve().toEntity(JsonNode.class);
    }
    /** Só devolve dados compatíveis com o pedido local; não propaga corpos/causas HTTP externos. */
    private Result verifiedResult(Request request,String providerId,long cents,ResponseEntity<JsonNode> response) {
        var result=response.getBody();
        if(result==null || !result.path("id").asText().matches("re_\\w+")
                || (providerId!=null && !providerId.equals(result.path("id").asText()))
                || !"refund".equals(result.path("object").asText()) || !request.paymentId().equals(result.path(FIELD_PAYMENT_INTENT).asText())
                || !"brl".equals(result.path("currency").asText()) || !result.path(FIELD_AMOUNT).isIntegralNumber()
                || result.path(FIELD_AMOUNT).asLong(-1)!=cents
                || !request.refundId().toString().equals(result.path("metadata").path("refund_id").asText())) throw review();
        var status=Status.valueOf(result.path("status").asText().toUpperCase(Locale.ROOT));
        String requestId=response.getHeaders().getFirst("Request-Id");
        if(requestId!=null && (!requestId.matches("req_\\w+") || requestId.length()>255)) requestId=null;
        return new Result(result.path("id").asText(),status,requestId);
    }
    /** Tolera somente desvio limitado; valores fracionários de centavo continuam proibidos. */
    private long validateRequest(Request request) {
        if(request==null || request.refundId()==null || request.idempotencyKey()==null || request.chargeId()==null
                || request.paymentId()==null || !request.paymentId().matches("pi_\\w+") || request.amount()==null
                || request.amount().signum()<=0 || request.firstAttemptAt()==null
                || request.firstAttemptAt().isAfter(clock.instant().plus(MAX_CLOCK_SKEW))) throw review();
        try { return request.amount().movePointRight(2).longValueExact(); }
        catch(ArithmeticException ex) { throw review(); }
    }
    private RefundFailure review() { return new RefundFailure(FailureKind.REVIEW_REQUIRED); }
}
