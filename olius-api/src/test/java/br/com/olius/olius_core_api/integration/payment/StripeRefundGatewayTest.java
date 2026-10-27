package br.com.olius.olius_core_api.integration.payment;

import java.math.BigDecimal;
import java.time.*;
import java.util.UUID;
import java.io.IOException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.*;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.client.RestClient;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class StripeRefundGatewayTest {
    private static final Instant NOW=Instant.parse("2026-10-26T12:00:00Z");
    private final RefundGateway.Request request=new RefundGateway.Request(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),"pi_fixture",new BigDecimal("19.90"),NOW.minusSeconds(60));
    private StripeRefundGateway gateway;
    private MockRestServiceServer server;
    @BeforeEach void setup() {
        var builder=RestClient.builder().baseUrl("https://api.stripe.com");
        server=MockRestServiceServer.bindTo(builder).build();
        gateway=new StripeRefundGateway(builder.build(),Clock.fixed(NOW,ZoneOffset.UTC));
    }
    private String payment() {
        return """
                {"id":"pi_fixture","object":"payment_intent","livemode":false,"status":"succeeded",
                "currency":"brl","amount_received":1990,"metadata":{"charge_id":"%s"}}
                """.formatted(request.chargeId());
    }
    private String refund(String status) {
        return """
                {"id":"re_fixture","object":"refund","payment_intent":"pi_fixture","currency":"brl",
                "amount":1990,"status":"%s","metadata":{"refund_id":"%s"}}
                """.formatted(status,request.refundId());
    }
    private void expectPayment(String body) {
        server.expect(requestTo("https://api.stripe.com/v1/payment_intents/pi_fixture"))
            .andExpect(method(HttpMethod.GET)).andRespond(withSuccess(body,MediaType.APPLICATION_JSON));
    }
    @Test void createsIntegralRefundWithStableKeyAndMinimalMetadata() {
        expectPayment(payment());
        server.expect(requestTo("https://api.stripe.com/v1/refunds"))
            .andExpect(method(HttpMethod.POST)).andExpect(header("Idempotency-Key",request.idempotencyKey().toString()))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("amount=1990")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("payment_intent=pi_fixture")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString(request.refundId().toString())))
            .andRespond(withSuccess(refund("succeeded"),MediaType.APPLICATION_JSON).header("Request-Id","req_fixture"));
        assertThat(gateway.create(request)).isEqualTo(new RefundGateway.Result("re_fixture",RefundGateway.Status.SUCCEEDED,"req_fixture"));
        server.verify();
    }
    @ParameterizedTest @ValueSource(strings={"pending","requires_action","failed","canceled"})
    void preservesNonSuccessfulProviderStatuses(String status) {
        expectPayment(payment());
        server.expect(requestTo("https://api.stripe.com/v1/refunds/re_fixture"))
            .andExpect(method(HttpMethod.GET)).andRespond(withSuccess(refund(status),MediaType.APPLICATION_JSON));
        assertThat(gateway.retrieve(request,"re_fixture").status().name()).isEqualTo(status.toUpperCase(java.util.Locale.ROOT));
        server.verify();
    }
    @Test void retrievesKnownRefundAfterTheCreationSafetyWindowWithoutPosting() {
        var old=new RefundGateway.Request(request.refundId(),request.idempotencyKey(),request.chargeId(),request.paymentId(),request.amount(),NOW.minus(Duration.ofDays(3)));
        expectPayment(payment());server.expect(requestTo("https://api.stripe.com/v1/refunds/re_fixture"))
            .andRespond(withSuccess(refund("succeeded"),MediaType.APPLICATION_JSON));
        assertThat(gateway.retrieve(old,"re_fixture").status()).isEqualTo(RefundGateway.Status.SUCCEEDED);server.verify();
    }
    @Test void oldUnknownCreationStopsBeforePosting() {
        var old=new RefundGateway.Request(request.refundId(),request.idempotencyKey(),request.chargeId(),request.paymentId(),request.amount(),NOW.minus(Duration.ofHours(24)));
        expectPayment(payment());
        assertThatThrownBy(()->gateway.create(old)).isInstanceOfSatisfying(RefundGateway.Failure.class,f->assertThat(f.kind()).isEqualTo(RefundGateway.FailureKind.RECONCILIATION_REQUIRED));
        server.verify();
    }
    @ParameterizedTest @ValueSource(strings={"live","unconfirmed","amount","owner","currency"})
    void rejectsAnUnverifiedParentPaymentBeforeRefunding(String mutation) {
        String body=switch(mutation) {
            case "live" -> payment().replace("false","true");
            case "unconfirmed" -> payment().replace("succeeded","processing");
            case "amount" -> payment().replace("1990","1991");
            case "owner" -> payment().replace(request.chargeId().toString(),UUID.randomUUID().toString());
            default -> payment().replace("brl","usd");
        };
        expectPayment(body);
        assertThatThrownBy(()->gateway.create(request)).isInstanceOfSatisfying(RefundGateway.Failure.class,f->assertThat(f.kind()).isEqualTo(RefundGateway.FailureKind.REVIEW_REQUIRED));server.verify();
    }
    @ParameterizedTest @ValueSource(strings={"amount","payment","identity","currency","status"})
    void rejectsIncompatibleRefundResults(String mutation) {
        String body=switch(mutation) {
            case "amount" -> refund("succeeded").replace("1990","1");
            case "payment" -> refund("succeeded").replace("pi_fixture","pi_other");
            case "identity" -> refund("succeeded").replace(request.refundId().toString(),UUID.randomUUID().toString());
            case "currency" -> refund("succeeded").replace("brl","usd");
            default -> refund("unexpected");
        };
        expectPayment(payment());server.expect(requestTo("https://api.stripe.com/v1/refunds/re_fixture")).andRespond(withSuccess(body,MediaType.APPLICATION_JSON));
        assertThatThrownBy(()->gateway.retrieve(request,"re_fixture")).isInstanceOf(RefundGateway.Failure.class);server.verify();
    }
    @ParameterizedTest @ValueSource(ints={400,401,403,409,429,500})
    void classifiesFailuresWithoutExposingExternalBodies(int status) {
        expectPayment(payment());server.expect(requestTo("https://api.stripe.com/v1/refunds"))
            .andRespond(withStatus(HttpStatusCode.valueOf(status)).body("sensitive-provider-detail"));
        assertThatThrownBy(()->gateway.create(request)).isInstanceOfSatisfying(RefundGateway.Failure.class,f->{
            assertThat(f.kind()).isEqualTo(status==409 || status==429 || status>=500 ? RefundGateway.FailureKind.TEMPORARY : RefundGateway.FailureKind.REVIEW_REQUIRED);
            assertThat(f.getMessage()).doesNotContain("sensitive-provider-detail");assertThat(f.getCause()).isNull();
        });server.verify();
    }
    @Test void networkTimeoutRemainsAnUnknownTemporaryResult() {
        expectPayment(payment());server.expect(requestTo("https://api.stripe.com/v1/refunds")).andRespond(withException(new IOException("sensitive-network-detail")));
        assertThatThrownBy(()->gateway.create(request)).isInstanceOfSatisfying(RefundGateway.Failure.class,f->{assertThat(f.kind()).isEqualTo(RefundGateway.FailureKind.TEMPORARY);assertThat(f.getCause()).isNull();});
    }
    @Test void refusesTransactionsAndFractionalCentsBeforeNetworkCalls() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try { assertThatThrownBy(()->gateway.create(request)).isInstanceOf(IllegalStateException.class); }
        finally { TransactionSynchronizationManager.setActualTransactionActive(false); }
        var invalid=new RefundGateway.Request(request.refundId(),request.idempotencyKey(),request.chargeId(),request.paymentId(),new BigDecimal("19.901"),request.firstAttemptAt());
        assertThatThrownBy(()->gateway.create(invalid)).isInstanceOf(RefundGateway.Failure.class);
    }
}
