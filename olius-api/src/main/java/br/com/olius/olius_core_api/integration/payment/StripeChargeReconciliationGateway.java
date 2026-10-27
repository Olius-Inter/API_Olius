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
    private static final String FIELD_STATUS="status";
    private static final String FIELD_PAYMENT_INTENT="payment_intent";
    private static final String PAYMENT_ID_PATTERN="pi_\\w+";
    private static final String FIELD_LIVEMODE="livemode";
    private static final String FIELD_OBJECT="object";
    private static final String FIELD_METADATA="metadata";
    private static final String FIELD_CHARGE_ID="charge_id";
    private static final String FIELD_HAS_MORE="has_more";
    private final RestClient client;
    private final Clock clock;
    public StripeChargeReconciliationGateway(String secretKey) {
        this(StripeCheckoutGateway.client(secretKey));
        if(secretKey==null || !secretKey.startsWith("sk_test_")) throw new IllegalArgumentException("Reconciliação exige sandbox");
    }
    StripeChargeReconciliationGateway(RestClient client) { this(client,Clock.systemUTC()); }
    StripeChargeReconciliationGateway(RestClient client,Clock clock) { this.client=client;this.clock=clock; }
    /** Confere identidade e cotação antes de consultar/expirar um Checkout; resultados incertos não liberam a cobrança. */
    @Override public Snapshot reconcile(Request request) {
        outside();
        if(request==null || request.chargeId()==null || request.key()==null || request.amount()==null
                || request.amount().signum()<=0 || request.createdAt()==null || request.expiresAt()==null) throw unavailable();
        try {
            String id=request.providerId();
            if(id==null) id=find(request);
            if(id==null) return new Snapshot(null,State.UNCERTAIN);
            if(!id.matches("cs_test_\\w+")) throw unavailable();
            JsonNode session=get(id);validate(session,request,id);
            if("open".equals(session.path(FIELD_STATUS).asText()) && !request.expiresAt().isAfter(clock.instant())) {
                expire(request,id);
                session=get(id);validate(session,request,id);
            }
            return inspect(request,id,session);
        } catch(RestClientException | IllegalArgumentException | ArithmeticException ex) { throw unavailable(); }
    }
    private Snapshot inspect(Request request,String id,JsonNode session) {
        String paymentStatus=session.path("payment_status").asText();
        String paymentId=session.path(FIELD_PAYMENT_INTENT).asText("");
        if("paid".equals(paymentStatus)) return new Snapshot(id,State.PAID);
        if(!"unpaid".equals(paymentStatus)) return new Snapshot(id,State.UNCERTAIN);
        boolean expired="expired".equals(session.path(FIELD_STATUS).asText());
        if(paymentId.isBlank()) return new Snapshot(id,withoutPaymentState(session));
        if(!paymentId.matches(PAYMENT_ID_PATTERN)) throw unavailable();
        var payment=client.get().uri("/v1/payment_intents/{id}",paymentId).retrieve().body(JsonNode.class);
        if(payment==null || !paymentId.equals(payment.path("id").asText())
                || !FIELD_PAYMENT_INTENT.equals(payment.path(FIELD_OBJECT).asText()) || payment.path(FIELD_LIVEMODE).asBoolean(true)
                || !"brl".equals(payment.path("currency").asText()) || !payment.path("amount").isIntegralNumber()
                || payment.path("amount").asLong(-1)!=request.amount().movePointRight(2).longValueExact()
                || !request.chargeId().toString().equals(payment.path(FIELD_METADATA).path(FIELD_CHARGE_ID).asText())) throw unavailable();
        if("succeeded".equals(payment.path(FIELD_STATUS).asText())) return new Snapshot(id,State.PAID);
        return new Snapshot(id,expired && "canceled".equals(payment.path(FIELD_STATUS).asText()) ? State.EXPIRED : State.UNCERTAIN);
    }
    /** Expiração pode disputar com pagamento; a decisão sempre depende da leitura posterior. */
    private void expire(Request request,String id) {
        try {
            client.post().uri("/v1/checkout/sessions/{id}/expire",id)
                .header("Idempotency-Key",request.key()+"-expire").retrieve().toBodilessEntity();
        } catch(RestClientException ignored) {
            // A consulta seguinte esclarece se houve expiração, pagamento ou resultado ainda incerto.
        }
    }
    private State withoutPaymentState(JsonNode session) {
        if("expired".equals(session.path(FIELD_STATUS).asText())) return State.EXPIRED;
        if("open".equals(session.path(FIELD_STATUS).asText())) return State.OPEN;
        return State.UNCERTAIN;
    }
    private JsonNode get(String id) { return client.get().uri("/v1/checkout/sessions/{id}",id).retrieve().body(JsonNode.class); }
    /** Recusa sessões com ambiente, cotação, vínculo, prazo ou formato de PaymentIntent incompatíveis. */
    private void validate(JsonNode session,Request request,String id) {
        if(session==null || !id.equals(session.path("id").asText()) || !"checkout.session".equals(session.path(FIELD_OBJECT).asText())
                || session.path(FIELD_LIVEMODE).asBoolean(true) || !"payment".equals(session.path("mode").asText())
                || !session.has(FIELD_PAYMENT_INTENT)
                || (!session.path(FIELD_PAYMENT_INTENT).isNull() && (!session.path(FIELD_PAYMENT_INTENT).isTextual()
                    || !session.path(FIELD_PAYMENT_INTENT).asText().matches(PAYMENT_ID_PATTERN)))
                || !"brl".equals(session.path("currency").asText()) || !session.path("amount_total").isIntegralNumber()
                || session.path("amount_total").asLong(-1)!=request.amount().movePointRight(2).longValueExact()
                || session.path("expires_at").asLong(-1)!=request.expiresAt().getEpochSecond()
                || !request.chargeId().toString().equals(session.path("client_reference_id").asText())
                || !request.chargeId().toString().equals(session.path(FIELD_METADATA).path(FIELD_CHARGE_ID).asText())) throw unavailable();
    }
    /** Procura uma identidade única em até mil sessões; busca incompleta ou múltipla permanece sem resolução. */
    private String find(Request request) {
        String found=null;
        String after=null;
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
            var scanned=scanSessions(result,request,found);
            found=scanned.found();after=scanned.last();
            if(!result.path(FIELD_HAS_MORE).asBoolean()) return found;
        }
        throw unavailable(); // Busca incompleta não é prova de ausência nem de unicidade.
    }
    private record SessionPage(String found,String last) { }
    /** Só aceita identidade única entre todos os resultados da busca; a paginação nunca é presumida completa. */
    private SessionPage scanSessions(JsonNode result,Request request,String previous) {
        String found=previous;
        String last=null;
        for(JsonNode session:result.path("data")) {
            String id=session.path("id").asText();
            if(!id.matches("cs_test_\\w+")) throw unavailable();
            if(request.chargeId().toString().equals(session.path(FIELD_METADATA).path(FIELD_CHARGE_ID).asText())) {
                validate(session,request,id);
                if(found!=null && !found.equals(id)) throw unavailable();
                found=id;
            }
            last=id;
        }
        return new SessionPage(found,last);
    }
    /** Lê uma página de eventos do provedor; candidatos ainda passam pela verificação financeira existente. */
    @Override public Page events(Instant from,Instant until,String after) {
        outside();
        if(from==null || until==null || from.isAfter(until) || (after!=null && !after.matches("evt_\\w+"))) throw unavailable();
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
                String id=event.path("id").asText();
                if(!id.matches("evt_\\w+")) throw unavailable();
                last=id;
                var candidate=notice(event,id);
                if(candidate!=null) notices.add(candidate);
            }
            return new Page(notices,result.path(FIELD_HAS_MORE).asBoolean() ? last : null);
        } catch(RestClientException | IllegalArgumentException ex) { throw unavailable(); }
    }
    /** Eventos externos ao domínio não entram na fila; o cursor da página ainda avança sobre eles. */
    private Notice notice(JsonNode event,String id) {
        var payment=event.path("data").path(FIELD_OBJECT);
        if(event.path(FIELD_LIVEMODE).asBoolean(true) || !"event".equals(event.path(FIELD_OBJECT).asText())
                || !"payment_intent.succeeded".equals(event.path("type").asText())
                || !FIELD_PAYMENT_INTENT.equals(payment.path(FIELD_OBJECT).asText())
                || !payment.path("id").asText().matches(PAYMENT_ID_PATTERN)) return null;
        String metadata=payment.path(FIELD_METADATA).path(FIELD_CHARGE_ID).asText();
        if(!metadata.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")) return null;
        return new Notice(id,payment.path("id").asText(),UUID.fromString(metadata));
    }
    private void list(JsonNode result) {
        if(result==null || !"list".equals(result.path(FIELD_OBJECT).asText()) || !result.path("data").isArray()
                || !result.path(FIELD_HAS_MORE).isBoolean() || (result.path(FIELD_HAS_MORE).asBoolean() && result.path("data").isEmpty())) throw unavailable();
    }
    private void outside() {
        if(TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Reconciliação HTTP exige transação encerrada");
    }
    private PaymentProviderUnavailableException unavailable() { return new PaymentProviderUnavailableException(); }
}
