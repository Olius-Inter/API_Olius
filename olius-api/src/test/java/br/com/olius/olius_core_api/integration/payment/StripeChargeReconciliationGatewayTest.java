package br.com.olius.olius_core_api.integration.payment;

import br.com.olius.olius_core_api.exception.PaymentProviderUnavailableException;
import br.com.olius.olius_core_api.integration.payment.ChargeReconciliationGateway.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.*;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class StripeChargeReconciliationGatewayTest {
    private MockRestServiceServer server;
    private StripeChargeReconciliationGateway gateway;
    private final Instant now=Instant.parse("2026-10-26T12:00:00Z");
    private final Request request=new Request(UUID.randomUUID(),UUID.randomUUID(),new BigDecimal("19.90"),now.minusSeconds(7200),now.minusSeconds(3600),"cs_test_fixture");
    @BeforeEach void setup() {
        var builder=RestClient.builder().baseUrl("https://api.stripe.com");server=MockRestServiceServer.bindTo(builder).build();gateway=new StripeChargeReconciliationGateway(builder.build(),java.time.Clock.fixed(now,java.time.ZoneOffset.UTC));
    }
    private String session(String state,String paymentStatus,String payment) {
        return """
            {"id":"cs_test_fixture","object":"checkout.session","mode":"payment","livemode":false,
            "currency":"brl","amount_total":1990,"status":"%s","payment_status":"%s","payment_intent":%s,
            "expires_at":%d,"client_reference_id":"%s","metadata":{"charge_id":"%s"}}
            """.formatted(state,paymentStatus,payment==null ? "null" : "\""+payment+"\"",request.expiresAt().getEpochSecond(),request.chargeId(),request.chargeId());
    }
    private void get(String body) {
        server.expect(requestTo("https://api.stripe.com/v1/checkout/sessions/cs_test_fixture")).andExpect(method(HttpMethod.GET)).andRespond(withSuccess(body,MediaType.APPLICATION_JSON));
    }
    @Test void expiredUnpaidCheckoutIsConfirmedWithoutAnotherCreation() {
        get(session("expired","unpaid",null));assertThat(gateway.reconcile(request)).isEqualTo(new Snapshot("cs_test_fixture",State.EXPIRED));server.verify();
    }
    @Test void validOpenCheckoutIsOnlyReadWithoutExpiringIt() {
        var future=new Request(request.chargeId(),request.key(),request.amount(),request.createdAt(),now.plusSeconds(3600),request.providerId());
        get(session("open","unpaid",null).replace(Long.toString(request.expiresAt().getEpochSecond()),Long.toString(future.expiresAt().getEpochSecond())));
        assertThat(gateway.reconcile(future).state()).isEqualTo(State.OPEN);server.verify();
    }
    @Test void openCheckoutIsExpiredExternallyAndReadAgain() {
        get(session("open","unpaid",null));
        server.expect(requestTo("https://api.stripe.com/v1/checkout/sessions/cs_test_fixture/expire"))
            .andExpect(method(HttpMethod.POST)).andExpect(header("Idempotency-Key",request.key()+"-expire")).andRespond(withSuccess());
        get(session("expired","unpaid",null));assertThat(gateway.reconcile(request).state()).isEqualTo(State.EXPIRED);server.verify();
    }
    @Test void raceWithSuccessfulPaymentIsNeverTreatedAsExpiration() {
        get(session("open","unpaid",null));
        server.expect(requestTo("https://api.stripe.com/v1/checkout/sessions/cs_test_fixture/expire"))
            .andRespond(withBadRequest().body("sensitive-provider-detail"));
        get(session("complete","paid","pi_paid"));assertThat(gateway.reconcile(request).state()).isEqualTo(State.PAID);server.verify();
    }
    @Test void absenceOfSessionDoesNotProveThatTheChargeCanBeClosed() {
        server.expect(requestTo(org.hamcrest.Matchers.containsString("/v1/checkout/sessions?")))
            .andRespond(withSuccess("{\"object\":\"list\",\"data\":[],\"has_more\":false}",MediaType.APPLICATION_JSON));
        var unknown=new Request(request.chargeId(),request.key(),request.amount(),request.createdAt(),request.expiresAt(),null);
        assertThat(gateway.reconcile(unknown)).isEqualTo(new Snapshot(null,State.UNCERTAIN));server.verify();
    }
    @Test void locatesAnUnassociatedSessionByVerifiedMetadata() {
        server.expect(requestTo(org.hamcrest.Matchers.containsString("/v1/checkout/sessions?")))
            .andRespond(withSuccess("{\"object\":\"list\",\"data\":["+session("expired","unpaid",null)+"],\"has_more\":false}",MediaType.APPLICATION_JSON));
        get(session("expired","unpaid",null));
        var unknown=new Request(request.chargeId(),request.key(),request.amount(),request.createdAt(),request.expiresAt(),null);
        assertThat(gateway.reconcile(unknown).providerId()).isEqualTo("cs_test_fixture");server.verify();
    }
    @ParameterizedTest @ValueSource(strings={"processing","requires_payment_method","canceled","succeeded"})
    void checksPaymentIntentBeforeClosingAnExpiredSession(String state) {
        get(session("expired","unpaid","pi_fixture"));
        String payment="""
            {"id":"pi_fixture","object":"payment_intent","livemode":false,"currency":"brl","amount":1990,
            "status":"%s","metadata":{"charge_id":"%s"}}
            """.formatted(state,request.chargeId());
        server.expect(requestTo("https://api.stripe.com/v1/payment_intents/pi_fixture")).andRespond(withSuccess(payment,MediaType.APPLICATION_JSON));
        State expected=switch(state) { case "canceled" -> State.EXPIRED;case "succeeded" -> State.PAID;default -> State.UNCERTAIN; };
        assertThat(gateway.reconcile(request).state()).isEqualTo(expected);server.verify();
    }
    @ParameterizedTest @ValueSource(strings={"amount","owner","live","expiry","missingPayment","expandedPayment","emptyPayment"})
    void rejectsUnrelatedOrIncompatibleSessions(String mutation) {
        String body=switch(mutation) {
            case "missingPayment" -> session("expired","unpaid",null).replace("\"payment_intent\":null,","");
            case "expandedPayment" -> session("expired","unpaid",null).replace("\"payment_intent\":null","\"payment_intent\":{}");
            case "emptyPayment" -> session("expired","unpaid",null).replace("\"payment_intent\":null","\"payment_intent\":\"\"");
            case "amount" -> session("expired","unpaid",null).replace("1990","1");
            case "owner" -> session("expired","unpaid",null).replace(request.chargeId().toString(),UUID.randomUUID().toString());
            case "live" -> session("expired","unpaid",null).replace("false","true");
            default -> session("expired","unpaid",null).replace(Long.toString(request.expiresAt().getEpochSecond()),"1");
        };
        get(body);assertThatThrownBy(()->gateway.reconcile(request)).isInstanceOf(PaymentProviderUnavailableException.class);server.verify();
    }
    @Test void discoversOnlySandboxEventsWithOliusMetadataAndPaginates() {
        String data="""
            {"object":"list","has_more":true,"data":[
            {"id":"evt_valid","object":"event","livemode":false,"type":"payment_intent.succeeded","data":{"object":{"id":"pi_valid","object":"payment_intent","metadata":{"charge_id":"%s"}}}},
            {"id":"evt_other","object":"event","livemode":false,"type":"payment_intent.succeeded","data":{"object":{"id":"pi_other","object":"payment_intent","metadata":{}}}}]}
            """.formatted(request.chargeId());
        server.expect(requestTo(org.hamcrest.Matchers.containsString("/v1/events?")))
            .andExpect(queryParam("type","payment_intent.succeeded")).andRespond(withSuccess(data,MediaType.APPLICATION_JSON));
        var page=gateway.events(now.minusSeconds(3600),now,null);
        assertThat(page.notices()).containsExactly(new Notice("evt_valid","pi_valid",request.chargeId()));assertThat(page.nextCursor()).isEqualTo("evt_other");server.verify();
    }
    @Test void rejectsBrokenPaginationAndDoesNotExposeProviderErrors() {
        server.expect(requestTo(org.hamcrest.Matchers.containsString("/v1/events?")))
            .andRespond(withSuccess("{\"object\":\"list\",\"has_more\":true,\"data\":[]}",MediaType.APPLICATION_JSON));
        var from=now.minusSeconds(60);
        assertThatThrownBy(()->gateway.events(from,now,null)).isInstanceOf(PaymentProviderUnavailableException.class);
    }
}
