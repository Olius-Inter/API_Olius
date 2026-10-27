package br.com.olius.olius_core_api.integration.payment;

import br.com.olius.olius_core_api.exception.PaymentProviderUnavailableException;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.client.RestClient;
import static org.assertj.core.api.Assertions.*;

class StripeCheckoutGatewayTest {
    private StripeCheckoutGateway gateway;
    private final AtomicReference<String> body=new AtomicReference<>();
    private final AtomicReference<String> key=new AtomicReference<>();
    private final AtomicInteger calls=new AtomicInteger();
    private String response;
    private int status=200;
    private final CheckoutGateway.CheckoutCharge charge=new CheckoutGateway.CheckoutCharge(
            UUID.randomUUID(),UUID.randomUUID(),"Plano & exemplo",new BigDecimal("19.90"),
            Instant.parse("2030-01-01T12:00:00Z"));

    @BeforeEach void start() throws Exception {
        response="""
                {"id":"cs_test_example","mode":"payment","status":"open","livemode":false,
                "currency":"brl","amount_total":1990,"client_reference_id":"%s",
                "expires_at":%d,"url":"https://checkout.stripe.com/c/pay/example"}
                """.formatted(charge.chargeId(),charge.expiresAt().getEpochSecond());
        var builder=RestClient.builder().baseUrl("https://api.stripe.com");
        var server=MockRestServiceServer.bindTo(builder).build();
        server.expect(ExpectedCount.manyTimes(),requestTo("https://api.stripe.com/v1/checkout/sessions"))
                .andExpect(method(org.springframework.http.HttpMethod.POST))
                .andExpect(request -> {
                    calls.incrementAndGet();
                    body.set(URLDecoder.decode(((MockClientHttpRequest)request).getBodyAsString(),StandardCharsets.UTF_8));
                    key.set(request.getHeaders().getFirst("Idempotency-Key"));
                })
                .andRespond(request -> withStatus(HttpStatusCode.valueOf(status))
                        .contentType(MediaType.APPLICATION_JSON).body(response).createResponse(request));
        gateway=new StripeCheckoutGateway(builder.build(),
                URI.create("http://localhost:3000/success"),URI.create("http://localhost:3000/cancel"));
    }

    @Test void sendsExactCentsAndStableIdentityWithoutRecurringCharge() {
        var result=gateway.create(charge);
        assertThat(result.providerId()).isEqualTo("cs_test_example");
        assertThat(result.expiresAt()).isEqualTo(charge.expiresAt());
        assertThat(body.get()).contains("mode=payment","allowed_payment_method_types[0]=card",
                "line_items[0][price_data][unit_amount]=1990","currency]=brl",
                "product_data][name]=Plano & exemplo","metadata[charge_id]="+charge.chargeId());
        assertThat(body.get()).doesNotContain("recurring","setup_future_usage");
        assertThat(key.get()).isEqualTo(charge.idempotencyKey().toString());
        gateway.create(charge);
        assertThat(key.get()).isEqualTo(charge.idempotencyKey().toString());
        assertThat(calls.get()).isEqualTo(2);
    }
    @Test void providerErrorsDoNotExposeTheExternalBody() {
        status=400;
        response="{\"error\":{\"message\":\"sensitive-value\"}}";
        assertThatThrownBy(() -> gateway.create(charge))
                .isInstanceOf(PaymentProviderUnavailableException.class)
                .hasMessage("SERVICE_UNAVAILABLE").hasNoCause();
    }
    @Test void rejectsInconsistentAmountsAndUntrustedUrls() {
        String valid=response;
        response=valid.replace("1990","1991");
        assertThatThrownBy(() -> gateway.create(charge)).isInstanceOf(PaymentProviderUnavailableException.class);
        response=valid.replace("checkout.stripe.com","untrusted.example");
        assertThatThrownBy(() -> gateway.create(charge)).isInstanceOf(PaymentProviderUnavailableException.class);
    }
    @Test void rejectsLiveResponsesAndIncompleteResponses() {
        String valid=response;
        response=valid.replace("false","true");
        assertThatThrownBy(() -> gateway.create(charge)).isInstanceOf(PaymentProviderUnavailableException.class);
        response="{}";
        assertThatThrownBy(() -> gateway.create(charge)).isInstanceOf(PaymentProviderUnavailableException.class);
    }
    @Test void refusesNetworkCallsInsideDatabaseTransactions() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThatThrownBy(() -> gateway.create(charge)).isInstanceOf(IllegalStateException.class);
            assertThat(calls.get()).isZero();
        } finally { TransactionSynchronizationManager.setActualTransactionActive(false); }
    }
    @Test void retrievesPersistedSessionWithoutAnotherPost() {
        var builder=RestClient.builder().baseUrl("https://api.stripe.com");
        var mock=MockRestServiceServer.bindTo(builder).build();
        mock.expect(requestTo("https://api.stripe.com/v1/checkout/sessions/cs_test_example"))
                .andExpect(method(org.springframework.http.HttpMethod.GET))
                .andExpect(headerDoesNotExist("Idempotency-Key"))
                .andRespond(withSuccess(response,MediaType.APPLICATION_JSON));
        var reader=new StripeCheckoutGateway(builder.build(),URI.create("http://localhost:3000/success"),
                URI.create("http://localhost:3000/cancel"));
        assertThat(reader.retrieve(charge,"cs_test_example").providerId()).isEqualTo("cs_test_example");
        mock.verify();
    }

    @Test void verifiesSucceededPaymentAgainstBothEventAndPaymentIntent() {
        var builder=RestClient.builder().baseUrl("https://api.stripe.com");
        var mock=MockRestServiceServer.bindTo(builder).build();
        mock.expect(requestTo("https://api.stripe.com/v1/events/evt_confirmed"))
                .andRespond(withSuccess("""
                        {"id":"evt_confirmed","object":"event","livemode":false,"type":"payment_intent.succeeded",
                         "created":1793000000,"data":{"object":{"id":"pi_confirmed","metadata":{"charge_id":"%s"}}}}
                        """.formatted(charge.chargeId()),MediaType.APPLICATION_JSON));
        mock.expect(requestTo("https://api.stripe.com/v1/payment_intents/pi_confirmed"))
                .andRespond(withSuccess("""
                        {"id":"pi_confirmed","object":"payment_intent","livemode":false,"status":"succeeded",
                         "currency":"brl","amount_received":1990,"metadata":{"charge_id":"%s"}}
                        """.formatted(charge.chargeId()),MediaType.APPLICATION_JSON));
        var reader=new StripeCheckoutGateway(builder.build(),URI.create("http://localhost:3000/success"),
                URI.create("http://localhost:3000/cancel"));
        var result=reader.verify("evt_confirmed","pi_confirmed");
        assertThat(result.chargeId()).isEqualTo(charge.chargeId());
        assertThat(result.amount()).isEqualByComparingTo("19.90");
        assertThat(result.paidAt()).isEqualTo(Instant.ofEpochSecond(1793000000));
        mock.verify();
    }
    @Test void refusesUnconfirmedPaymentFromProvider() {
        var builder=RestClient.builder().baseUrl("https://api.stripe.com");
        var mock=MockRestServiceServer.bindTo(builder).build();
        mock.expect(requestTo("https://api.stripe.com/v1/events/evt_pending"))
                .andRespond(withSuccess("""
                        {"id":"evt_pending","object":"event","livemode":false,"type":"payment_intent.succeeded",
                         "created":1793000000,"data":{"object":{"id":"pi_pending","metadata":{"charge_id":"%s"}}}}
                        """.formatted(charge.chargeId()),MediaType.APPLICATION_JSON));
        mock.expect(requestTo("https://api.stripe.com/v1/payment_intents/pi_pending"))
                .andRespond(withSuccess("""
                        {"id":"pi_pending","object":"payment_intent","livemode":false,"status":"processing",
                         "currency":"brl","amount_received":1990,"metadata":{"charge_id":"%s"}}
                        """.formatted(charge.chargeId()),MediaType.APPLICATION_JSON));
        var reader=new StripeCheckoutGateway(builder.build(),URI.create("http://localhost:3000/success"),
                URI.create("http://localhost:3000/cancel"));
        assertThatThrownBy(()->reader.verify("evt_pending","pi_pending"))
                .isInstanceOf(PaymentProviderUnavailableException.class);
        mock.verify();
    }

    @Test void doesNotRoundFractionalCentsOrSendInvalidAmounts() {
        var invalid=new CheckoutGateway.CheckoutCharge(charge.chargeId(),charge.idempotencyKey(),
                "Plano",new BigDecimal("19.999"),charge.expiresAt());
        assertThatThrownBy(() -> gateway.create(invalid)).isInstanceOf(ArithmeticException.class);
        var zero=new CheckoutGateway.CheckoutCharge(charge.chargeId(),charge.idempotencyKey(),
                "Plano",BigDecimal.ZERO,charge.expiresAt());
        assertThatThrownBy(() -> gateway.create(zero)).isInstanceOf(IllegalArgumentException.class);
        assertThat(calls.get()).isZero();
    }
}
