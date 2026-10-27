package br.com.olius.olius_core_api;

import br.com.olius.olius_core_api.exception.PaymentProviderUnavailableException;
import br.com.olius.olius_core_api.integration.payment.CheckoutGateway;
import br.com.olius.olius_core_api.integration.payment.CheckoutGateway.CheckoutSession;
import br.com.olius.olius_core_api.integration.payment.PaymentVerificationGateway;
import br.com.olius.olius_core_api.integration.payment.PaymentVerificationGateway.VerifiedPayment;
import br.com.olius.olius_core_api.repository.PaymentEventRepository;
import br.com.olius.olius_core_api.security.StripeWebhookSignatureVerifier;
import br.com.olius.olius_core_api.service.*;
import br.com.olius.olius_core_api.testfixture.OfficialPostgres;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@Testcontainers @AutoConfigureMockMvc
@Import(StripeWebhookIntegrationTest.SignatureConfiguration.class)
@SpringBootTest(properties={"spring.config.import=","olius.security.ephemeral-keys=true",
        "olius.payments.stripe.enabled=false","olius.security.allowed-origins=https://frontend.test"})
class StripeWebhookIntegrationTest {
    private static final String PATH="/api/v1/webhooks/stripe";
    private static final String SECRET="whsec_integration_fixture_only";
    @Container static final OfficialPostgres POSTGRES=new OfficialPostgres();
    @DynamicPropertySource static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url",POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username",()->OfficialPostgres.API_USER);
        properties.add("spring.datasource.password",()->OfficialPostgres.API_PASSWORD);
    }
    @TestConfiguration static class SignatureConfiguration {
        @Bean StripeWebhookSignatureVerifier signatureVerifier() {
            return new StripeWebhookSignatureVerifier(SECRET,Clock.systemUTC());
        }
    }
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired InitialCheckoutService checkout;
    @Autowired StripePaymentEventProcessor processor;
    @Autowired PaymentEventTransactions transactions;
    @MockitoBean CheckoutGateway gateway;
    @MockitoBean PaymentVerificationGateway verification;
    private JdbcTemplate owner;
    private UUID ownerId;
    private UUID chargeId;
    private String paymentId;
    private final Map<String,VerifiedPayment> confirmed=new ConcurrentHashMap<>();

    @BeforeEach void fixture() {
        owner=new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword()));
        ownerId=UUID.randomUUID(); UUID address=UUID.randomUUID();UUID type=UUID.randomUUID();UUID plan=UUID.randomUUID();
        String qr=UUID.randomUUID().toString();
        owner.update("INSERT INTO users(id,name,email,password_hash,user_type) VALUES (?,'Fixture',?,'test-hash','ESTABLISHMENT')",ownerId,ownerId+"@example.test");
        owner.update("INSERT INTO addresses(id,owner_kind,state,city,neighborhood,street,number,cep) VALUES (?,'ESTABLISHMENT','SP','Cidade','Bairro','Rua','1','00000000')",address);
        owner.update("INSERT INTO establishment_type(id,name) VALUES (?,?)",type,"Tipo-"+type);
        owner.update("INSERT INTO user_qr_code(user_id,qr_token,user_type) VALUES (?,?,'ESTABLISHMENT')",ownerId,qr);
        owner.update("INSERT INTO establishment(id,cnpj,qr_token,type_id,address_id) VALUES (?,?,?,?,?)",ownerId,
                Long.toString(ThreadLocalRandom.current().nextLong(10000000000000L,99999999999999L)),qr,type,address);
        owner.update("INSERT INTO subscription_plan(id,name,monthly_price,volume_limit_liters,collection_limit) VALUES (?,?,19.90,100,2)",plan,"Plano-"+plan);
        when(gateway.create(any())).thenAnswer(invocation -> {
            CheckoutGateway.CheckoutCharge input=invocation.getArgument(0);
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return new CheckoutSession("cs_test_"+input.chargeId().toString().replace("-",""),
                    URI.create("https://checkout.stripe.com/c/pay/test"),input.expiresAt());
        });
        chargeId=checkout.create(ownerId,plan,UUID.randomUUID()).chargeId();
        paymentId="pi_"+UUID.randomUUID().toString().replace("-","");
        confirmed.put(paymentId,confirmed(paymentId,new BigDecimal("19.90")));
        when(verification.verify(anyString(),anyString())).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            String event=invocation.getArgument(0);
            assertThat(owner.queryForObject("SELECT status::text FROM payment_provider_event WHERE provider_event_id=?",String.class,event)).isEqualTo("PROCESSING");
            var payment=confirmed.get(invocation.getArgument(1));
            if(payment==null) throw new PaymentProviderUnavailableException();
            return payment;
        });
    }
    private VerifiedPayment confirmed(String id,BigDecimal amount) {
        return new VerifiedPayment(id,chargeId,amount,"BRL",Instant.now().minusSeconds(2));
    }

    @Test void signatureReceiptQueuesThenAtomicallyActivatesCycleWithSystemAudit() throws Exception {
        String event=eventId();receive(event,paymentId);
        assertThat(state(event)).isEqualTo("PENDING");
        assertThat(count("payment")).isZero();assertThat(count("subscription_cycle")).isZero();
        processor.processDue();
        assertThat(state(event)).isEqualTo("PROCESSED");
        assertThat(count("payment")).isEqualTo(1);assertThat(count("payment_application")).isEqualTo(1);
        assertThat(count("subscription_cycle")).isEqualTo(1);
        assertThat(owner.queryForObject("SELECT status::text FROM establishment_subscription WHERE establishment_id=?",String.class,ownerId)).isEqualTo("ACTIVE");
        assertThat(owner.queryForObject("SELECT status::text FROM billing_charge WHERE id=?",String.class,chargeId)).isEqualTo("PAID");
        assertThat(owner.queryForObject("SELECT count(*) FROM payment_log WHERE establishment_id=? AND actor_kind='SYSTEM' AND performed_by IS NULL",Integer.class,ownerId)).isEqualTo(1);
        assertThat(owner.queryForObject("SELECT EXTRACT(EPOCH FROM ends_at-starts_at)>0 FROM subscription_cycle WHERE establishment_id=?",Boolean.class,ownerId)).isTrue();
        assertThat(owner.queryForObject("SELECT monthly_price FROM subscription_cycle WHERE establishment_id=?",BigDecimal.class,ownerId)).isEqualByComparingTo("19.90");
    }
    @Test void sameEventAndDifferentEventsForOnePaymentDoNotDuplicateBenefits() throws Exception {
        String event=eventId();receive(event,paymentId);receive(event,paymentId);processor.processDue();
        receive(event,paymentId);receive(eventId(),paymentId);processor.processDue();
        assertThat(count("payment")).isEqualTo(1);assertThat(count("payment_application")).isEqualTo(1);
        assertThat(count("subscription_cycle")).isEqualTo(1);assertThat(count("payment_refund")).isZero();
    }
    @Test void invalidSignatureModifiedBodyAndOversizedPayloadDoNotEnterDatabase() throws Exception {
        byte[] body=payload(eventId(),paymentId);
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content("{}")
                .header("Stripe-Signature",signature(body))).andExpect(status().isBadRequest());
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(new byte[262145]))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(verification);assertThat(count("payment")).isZero();
    }
    @Test void ignoresSignedSandboxPaymentWithoutOliusMetadata() throws Exception {
        String event=eventId();
        byte[] body=json.writeValueAsBytes(Map.of("id",event,"livemode",false,"type","payment_intent.succeeded",
                "data",Map.of("object",Map.of("id",paymentId,"object","payment_intent","metadata",Map.of()))));
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(body).header("Stripe-Signature",signature(body)))
                .andExpect(status().isNoContent());
        assertThat(owner.queryForObject("SELECT count(*) FROM payment_provider_event WHERE provider_event_id=?",Integer.class,event)).isZero();
    }
    @Test void temporaryFailureRemainsDurableAndCanBeRetried() throws Exception {
        confirmed.remove(paymentId);String event=eventId();receive(event,paymentId);processor.processDue();
        assertThat(state(event)).isEqualTo("FAILED");assertThat(count("payment")).isZero();
        assertThat(owner.queryForObject("SELECT last_error FROM payment_provider_event WHERE provider_event_id=?",String.class,event)).isEqualTo("PAYMENT_PROCESSING_FAILED");
        confirmed.put(paymentId,confirmed(paymentId,new BigDecimal("19.90")));
        owner.update("UPDATE payment_provider_event SET next_attempt_at=clock_timestamp()-interval '1 second' WHERE provider_event_id=?",event);
        processor.processDue();
        assertThat(state(event)).isEqualTo("PROCESSED");
        assertThat(owner.queryForObject("SELECT attempts FROM payment_provider_event WHERE provider_event_id=?",Integer.class,event)).isEqualTo(2);
    }
    @Test void amountMismatchDoesNotCreatePaymentOrApplyBenefit() throws Exception {
        confirmed.put(paymentId,confirmed(paymentId,new BigDecimal("1.00")));
        String event=eventId();receive(event,paymentId);processor.processDue();
        assertThat(state(event)).isEqualTo("FAILED");assertThat(count("payment")).isZero();
        assertThat(count("subscription_cycle")).isZero();assertThat(count("payment_application")).isZero();
    }
    @Test void concurrentWorkersPreserveOnePaymentAndCycle() throws Exception {
        String first=eventId(),second=eventId();receive(first,paymentId);receive(second,paymentId);
        var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            Runnable job=()-> { try { start.await();processor.processDue(); } catch(InterruptedException ex) { Thread.currentThread().interrupt();throw new IllegalStateException(ex); } };
            var a=pool.submit(job);var b=pool.submit(job);start.countDown();a.get(30,TimeUnit.SECONDS);b.get(30,TimeUnit.SECONDS);
        }
        assertThat(state(first)).isEqualTo("PROCESSED");assertThat(state(second)).isEqualTo("PROCESSED");
        assertThat(count("payment")).isEqualTo(1);assertThat(count("subscription_cycle")).isEqualTo(1);
    }
    @Test void abandonedProcessingIsRecoveredByWorkerAfterLeaseExpires() throws Exception {
        String event=eventId();receive(event,paymentId);
        UUID id=owner.queryForObject("SELECT id FROM payment_provider_event WHERE provider_event_id=?",UUID.class,event);
        assertThat(transactions.claim(id)).hasSize(1);
        assertThat(state(event)).isEqualTo("PROCESSING");
        assertThat(count("payment")).isZero();
        // Simulate a process that stopped after claiming the persisted event.
        owner.update("UPDATE payment_provider_event SET next_attempt_at=clock_timestamp()-interval '1 second' WHERE id=?",id);
        processor.processDue();
        assertThat(state(event)).isEqualTo("PROCESSED");
        assertThat(owner.queryForObject("SELECT attempts FROM payment_provider_event WHERE id=?",Integer.class,id)).isEqualTo(2);
        assertThat(count("payment")).isEqualTo(1);
        assertThat(count("payment_application")).isEqualTo(1);
        assertThat(count("subscription_cycle")).isEqualTo(1);
        processor.processDue();
        assertThat(count("payment_application")).isEqualTo(1);
    }

    @Test void staleWorkerCannotFinishOrFailANewerClaim() throws Exception {
        String event=eventId();receive(event,paymentId);
        UUID id=owner.queryForObject("SELECT id FROM payment_provider_event WHERE provider_event_id=?",UUID.class,event);
        var old=transactions.claim(id).getFirst();
        owner.update("UPDATE payment_provider_event SET next_attempt_at=clock_timestamp()-interval '1 second' WHERE id=?",id);
        var current=transactions.claim(id).getFirst();
        transactions.complete(old,confirmed.get(paymentId));transactions.fail(old);
        assertThat(count("payment")).isZero();assertThat(state(event)).isEqualTo("PROCESSING");
        transactions.complete(current,confirmed.get(paymentId));assertThat(state(event)).isEqualTo("PROCESSED");
    }
    @Test void secondRealPaymentForSameBenefitCreatesOnlyRequestedRefund() throws Exception {
        receive(eventId(),paymentId);processor.processDue();
        String another="pi_"+UUID.randomUUID().toString().replace("-","");
        confirmed.put(another,confirmed(another,new BigDecimal("19.90")));
        String event=eventId();receive(event,another);processor.processDue();
        assertThat(state(event)).isEqualTo("PROCESSED");assertThat(count("payment")).isEqualTo(2);
        assertThat(count("payment_application")).isEqualTo(1);assertThat(count("subscription_cycle")).isEqualTo(1);
        assertThat(owner.queryForObject("SELECT status::text FROM payment_refund WHERE payment_id IN (SELECT id FROM payment WHERE establishment_id=?)",String.class,ownerId)).isEqualTo("REQUESTED");
    }
    @Test void closedChargeFailureRollsBackPaymentAndBenefitsButPreservesEvent() throws Exception {
        owner.update("UPDATE billing_charge SET status='EXPIRED',closed_at=clock_timestamp() WHERE id=?",chargeId);
        String event=eventId();receive(event,paymentId);processor.processDue();
        assertThat(state(event)).isEqualTo("FAILED");assertThat(count("payment")).isZero();
        assertThat(count("subscription_cycle")).isZero();
        assertThat(owner.queryForObject("SELECT count(*) FROM payment_log WHERE establishment_id=?",Integer.class,ownerId)).isZero();
    }
    @Test void frontendPreflightAllowsTheIdempotencyHeader() throws Exception {
        mvc.perform(options("/api/v1/billing/initial-checkouts").header("Origin","https://frontend.test")
                .header("Access-Control-Request-Method","POST")
                .header("Access-Control-Request-Headers","authorization,content-type,idempotency-key"))
                .andExpect(status().isOk()).andExpect(header().string("Access-Control-Allow-Headers",org.hamcrest.Matchers.containsString("idempotency-key")));
    }
    private byte[] payload(String event,String payment) {
        return json.writeValueAsBytes(Map.of("id",event,"object","event","livemode",false,"type","payment_intent.succeeded",
                "data",Map.of("object",Map.of("id",payment,"object","payment_intent","metadata",Map.of("charge_id",chargeId.toString())))));
    }
    private void receive(String event,String payment) throws Exception {
        byte[] body=payload(event,payment);
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(body).header("Stripe-Signature",signature(body)))
                .andExpect(status().isNoContent());
    }
    private String signature(byte[] body) throws Exception {
        long time=Instant.now().getEpochSecond();
        var mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
        mac.update((time+".").getBytes(StandardCharsets.UTF_8));
        return "t="+time+",v1="+HexFormat.of().formatHex(mac.doFinal(body));
    }
    private int count(String table) {
        if("payment_refund".equals(table)) return owner.queryForObject("SELECT count(*) FROM payment_refund WHERE payment_id IN (SELECT id FROM payment WHERE establishment_id=?)",Integer.class,ownerId);
        return owner.queryForObject("SELECT count(*) FROM "+table+" WHERE establishment_id=?",Integer.class,ownerId);
    }
    private String state(String event) { return owner.queryForObject("SELECT status::text FROM payment_provider_event WHERE provider_event_id=?",String.class,event); }
    private String eventId() { return "evt_"+UUID.randomUUID().toString().replace("-",""); }
}
