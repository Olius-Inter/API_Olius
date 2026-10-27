package br.com.olius.olius_core_api.integration.payment;

import br.com.olius.olius_core_api.exception.PaymentProviderUnavailableException;
import java.time.Instant;
import java.time.Clock;
import java.util.ArrayList;
import java.util.UUID;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;

/** Apenas consulta/encerra tentativas existentes; nunca cria um Checkout ou presume ausência. */
public final class StripeChargeReconciliationGateway implements ChargeReconciliationGateway {
    private final RestClient client;
    private final Clock clock;
    public StripeChargeReconciliationGateway(String secretKey) {
        this(StripeCheckoutGateway.client(secretKey));
        if(secretKey==null || !secretKey.startsWith("sk_test_")) throw new IllegalArgumentException("Reconciliação exige sandbox");
    }
    StripeChargeReconciliationGateway(RestClient client) { this(client,Clock.systemUTC()); }
    StripeChargeReconciliationGateway(RestClient client,Clock clock) { this.client=client;this.clock=clock; }
    @Override public Snapshot reconcile(Request request) {
        outside();
        if(request==null || request.chargeId()==null || request.key()==null || request.amount()==null
                || request.amount().signum()<=0 || request.createdAt()==null || request.expiresAt()==null) throw unavailable();
        try {
            String id=request.providerId();
            if(id==null) id=find(request);
            if(id==null) return new Snapshot(null,State.UNCERTAIN);
            if(!id.matches("cs_test_[A-Za-z0-9_]+")) throw unavailable();
            JsonNode session=get(id);validate(session,request,id);
            if("open".equals(session.path("status").asText()) && !request.expiresAt().isAfter(clock.instant())) {
                try {
                    client.post().uri("/v1/checkout/sessions/{id}/expire",id)
                        .header("Idempotency-Key",request.key()+"-expire").retrieve().toBodilessEntity();
                } catch(RestClientException ignored) {
                    // Timeout ou corrida com pagamento: o estado será lido novamente, nunca presumido.
                }
                session=get(id);validate(session,request,id);
            }
            return inspect(request,id,session);
        } catch(RestClientException | IllegalArgumentException | ArithmeticException ex) { throw unavailable(); }
    }
    private Snapshot inspect(Request request,String id,JsonNode session) {
        String paymentStatus=session.path("payment_status").asText();
        String paymentId=session.path("payment_intent").asText("");
        if("paid".equals(paymentStatus)) return new Snapshot(id,State.PAID);
        if(!"unpaid".equals(paymentStatus)) return new Snapshot(id,State.UNCERTAIN);
        boolean expired="expired".equals(session.path("status").asText());
        if(paymentId.isBlank()) return new Snapshot(id,expired ? State.EXPIRED : "open".equals(session.path("status").asText()) ? State.OPEN : State.UNCERTAIN);
        if(!paymentId.matches("pi_[A-Za-z0-9_]+")) throw unavailable();
        var payment=client.get().uri("/v1/payment_intents/{id}",paymentId).retrieve().body(JsonNode.class);
        if(payment==null || !paymentId.equals(payment.path("id").asText())
                || !"payment_intent".equals(payment.path("object").asText()) || payment.path("livemode").asBoolean(true)
                || !"brl".equals(payment.path("currency").asText()) || !payment.path("amount").isIntegralNumber()
                || payment.path("amount").asLong(-1)!=request.amount().movePointRight(2).longValueExact()
                || !request.chargeId().toString().equals(payment.path("metadata").path("charge_id").asText())) throw unavailable();
        if("succeeded".equals(payment.path("status").asText())) return new Snapshot(id,State.PAID);
        return new Snapshot(id,expired && "canceled".equals(payment.path("status").asText()) ? State.EXPIRED : State.UNCERTAIN);
    }
    private JsonNode get(String id) { return client.get().uri("/v1/checkout/sessions/{id}",id).retrieve().body(JsonNode.class); }
    private void validate(JsonNode session,Request request,String id) {
        if(session==null || !id.equals(session.path("id").asText()) || !"checkout.session".equals(session.path("object").asText())
                || session.path("livemode").asBoolean(true) || !"payment".equals(session.path("mode").asText())
                || !session.has("payment_intent")
                || (!session.path("payment_intent").isNull() && (!session.path("payment_intent").isTextual()
                    || !session.path("payment_intent").asText().matches("pi_[A-Za-z0-9_]+")))
                || !"brl".equals(session.path("currency").asText()) || !session.path("amount_total").isIntegralNumber()
                || session.path("amount_total").asLong(-1)!=request.amount().movePointRight(2).longValueExact()
                || session.path("expires_at").asLong(-1)!=request.expiresAt().getEpochSecond()
                || !request.chargeId().toString().equals(session.path("client_reference_id").asText())
                || !request.chargeId().toString().equals(session.path("metadata").path("charge_id").asText())) throw unavailable();
    }
    private String find(Request request) {
        String found=null,after=null;
        for(int page=0;page<10;page++) {
            String cursor=after;
            var result=client.get().uri(builder->{
                builder.path("/v1/checkout/sessions").queryParam("limit",100)
                    .queryParam("created[gte]",Math.max(0,request.createdAt().minusSeconds(60).getEpochSecond()))
                    .queryParam("created[lte]",request.expiresAt().getEpochSecond());
                if(cursor!=null) builder.queryParam("starting_after",cursor);
                return builder.build();
            }).retrieve().body(JsonNode.class);
            list(result);
            for(JsonNode session:result.path("data")) {
                String id=session.path("id").asText();
                if(!id.matches("cs_test_[A-Za-z0-9_]+")) throw unavailable();
                if(request.chargeId().toString().equals(session.path("metadata").path("charge_id").asText())) {
                    validate(session,request,id);
                    if(found!=null && !found.equals(id)) throw unavailable();
                    found=id;
                }
                after=id;
            }
            if(!result.path("has_more").asBoolean()) return found;
        }
        throw unavailable(); // Busca incompleta não é prova de ausência nem de unicidade.
    }
    @Override public Page events(Instant from,Instant until,String after) {
        outside();
        if(from==null || until==null || from.isAfter(until) || (after!=null && !after.matches("evt_[A-Za-z0-9_]+"))) throw unavailable();
        try {
            var result=client.get().uri(builder->{
                builder.path("/v1/events").queryParam("type","payment_intent.succeeded").queryParam("limit",100)
                    .queryParam("created[gte]",from.getEpochSecond()).queryParam("created[lte]",until.getEpochSecond());
                if(after!=null) builder.queryParam("starting_after",after);
                return builder.build();
            }).retrieve().body(JsonNode.class);
            list(result);String last=null;
            var notices=new ArrayList<Notice>();
            for(JsonNode event:result.path("data")) {
                String id=event.path("id").asText();if(!id.matches("evt_[A-Za-z0-9_]+")) throw unavailable();last=id;
                var payment=event.path("data").path("object");
                if(event.path("livemode").asBoolean(true) || !"event".equals(event.path("object").asText())
                        || !"payment_intent.succeeded".equals(event.path("type").asText())
                        || !"payment_intent".equals(payment.path("object").asText())
                        || !payment.path("id").asText().matches("pi_[A-Za-z0-9_]+")) continue;
                String metadata=payment.path("metadata").path("charge_id").asText();
                if(!metadata.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")) continue;
                notices.add(new Notice(id,payment.path("id").asText(),UUID.fromString(metadata)));
            }
            return new Page(notices,result.path("has_more").asBoolean() ? last : null);
        } catch(RestClientException | IllegalArgumentException ex) { throw unavailable(); }
    }
    private void list(JsonNode result) {
        if(result==null || !"list".equals(result.path("object").asText()) || !result.path("data").isArray()
                || !result.path("has_more").isBoolean() || (result.path("has_more").asBoolean() && result.path("data").isEmpty())) throw unavailable();
    }
    private void outside() {
        if(TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Reconciliação HTTP exige transação encerrada");
    }
    private PaymentProviderUnavailableException unavailable() { return new PaymentProviderUnavailableException(); }
}
