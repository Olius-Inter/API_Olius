package br.com.olius.olius_core_api;

import br.com.olius.olius_core_api.exception.*;
import br.com.olius.olius_core_api.integration.payment.CheckoutGateway;
import br.com.olius.olius_core_api.integration.payment.CheckoutGateway.CheckoutCharge;
import br.com.olius.olius_core_api.integration.payment.CheckoutGateway.CheckoutSession;
import br.com.olius.olius_core_api.integration.payment.PaymentVerificationGateway.VerifiedPayment;
import br.com.olius.olius_core_api.repository.PaymentEventRepository;
import br.com.olius.olius_core_api.service.*;
import br.com.olius.olius_core_api.testfixture.OfficialPostgres;
import java.math.BigDecimal;
import java.net.URI;
import java.sql.Timestamp;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.password.PasswordEncoder;
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
@SpringBootTest(properties={"spring.config.import=","olius.security.ephemeral-keys=true",
        "olius.payments.stripe.enabled=false","olius.payments.renewal-checkout-ttl=1h"})
class UpgradeCheckoutIntegrationTest {
    private static final String ROUTE="/api/v1/billing/upgrade-checkouts";
    @Container static final OfficialPostgres POSTGRES=new OfficialPostgres();
    @DynamicPropertySource static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url",POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username",()->OfficialPostgres.API_USER);
        properties.add("spring.datasource.password",()->OfficialPostgres.API_PASSWORD);
    }
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired PasswordEncoder passwords;
    @Autowired RenewalCheckoutService renewal;
    @Autowired UpgradeCheckoutService upgrade;
    @Autowired PaymentEventRepository events;
    @Autowired PaymentEventTransactions paymentTransactions;
    @MockitoBean CheckoutGateway gateway;
    private JdbcTemplate owner;
    private UUID ownerId,subscription,cycle,plan;
    private Instant starts,ends;
    private String email;
    private final AtomicReference<CheckoutCharge> sent=new AtomicReference<>();

    @BeforeEach void seed() {
        owner=new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword()));
        ownerId=UUID.randomUUID();subscription=UUID.randomUUID();cycle=UUID.randomUUID();plan=UUID.randomUUID();
        UUID address=UUID.randomUUID(),type=UUID.randomUUID(),oldPlan=UUID.randomUUID();String qr=UUID.randomUUID().toString();
        email=ownerId+"@example.test";
        owner.update("INSERT INTO users(id,name,email,password_hash,user_type) VALUES (?,'Fixture',?,?,'ESTABLISHMENT')",ownerId,email,passwords.encode("Password1!"));
        owner.update("INSERT INTO addresses(id,owner_kind,state,city,neighborhood,street,number,cep) VALUES (?,'ESTABLISHMENT','SP','Cidade','Bairro','Rua','1','00000000')",address);
        owner.update("INSERT INTO establishment_type(id,name) VALUES (?,?)",type,"Tipo-"+type);
        owner.update("INSERT INTO user_qr_code(user_id,qr_token,user_type) VALUES (?,?,'ESTABLISHMENT')",ownerId,qr);
        owner.update("INSERT INTO establishment(id,cnpj,qr_token,type_id,address_id) VALUES (?,?,?,?,?)",ownerId,
                Long.toString(ThreadLocalRandom.current().nextLong(10000000000000L,99999999999999L)),qr,type,address);
        owner.update("INSERT INTO subscription_plan(id,name,monthly_price,volume_limit_liters,collection_limit) VALUES (?,?,19.90,50,2)",oldPlan,"Atual-"+oldPlan);
        owner.update("INSERT INTO subscription_plan(id,name,monthly_price,volume_limit_liters,collection_limit) VALUES (?,?,39.90,220,4)",plan,"Próximo-"+plan);
        var start=ZonedDateTime.now(ZoneId.of("America/Sao_Paulo")).minusDays(1).truncatedTo(ChronoUnit.SECONDS);
        starts=start.toInstant();ends=start.plusMonths(1).toInstant();
        owner.update("INSERT INTO establishment_subscription(id,establishment_id,status,activated_at,created_at) VALUES (?,?,'ACTIVE',?,?)",subscription,ownerId,Timestamp.from(starts),Timestamp.from(starts));
        owner.update("""
                INSERT INTO subscription_cycle(id,subscription_id,establishment_id,cycle_number,starts_at,ends_at,
                anchor_day,anchor_local_time,anchor_timezone,plan_id,plan_name,monthly_price,volume_limit_liters,collection_limit)
                VALUES (?,?,?,1,?,?,?,?, 'America/Sao_Paulo',?,'Plano atual',19.90,50,2)
                """,cycle,subscription,ownerId,Timestamp.from(starts),Timestamp.from(ends),start.getDayOfMonth(),
                java.sql.Time.valueOf(start.toLocalTime()),oldPlan);
        when(gateway.create(any())).thenAnswer(invocation -> external(invocation.getArgument(0)));
        when(gateway.retrieve(any(),anyString())).thenAnswer(invocation -> external(invocation.getArgument(0)));
    }
    private CheckoutSession external(CheckoutCharge charge) {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        assertThat(owner.queryForObject("SELECT count(*) FROM billing_charge WHERE id=?",Integer.class,charge.chargeId())).isEqualTo(1);
        sent.set(charge);
        return new CheckoutSession("cs_test_"+charge.chargeId().toString().replace("-",""),
                URI.create("https://checkout.stripe.com/c/pay/upgrade"),charge.expiresAt());
    }
    private String cycleSnapshot() { return owner.queryForObject("SELECT to_jsonb(c)::text FROM subscription_cycle c WHERE id=?",String.class,cycle); }
    private void confirm(UUID chargeId) {
        String event="evt_"+UUID.randomUUID().toString().replace("-","");String payment="pi_"+UUID.randomUUID().toString().replace("-","");
        events.receive(event,payment);
        UUID id=owner.queryForObject("SELECT id FROM payment_provider_event WHERE provider_event_id=?",UUID.class,event);
        var claim=paymentTransactions.claim(id).getFirst();
        var amount=owner.queryForObject("SELECT amount FROM billing_charge WHERE id=?",BigDecimal.class,chargeId);
        paymentTransactions.complete(claim,new VerifiedPayment(payment,chargeId,amount,"BRL",Instant.now().minusSeconds(2)));
    }
    private int charges() { return owner.queryForObject("SELECT count(*) FROM billing_charge WHERE establishment_id=?",Integer.class,ownerId); }

    @Test void quoteChargesFullDifferenceAndDoesNotChangeBenefitsBeforePayment() {
        String before=cycleSnapshot();var result=upgrade.create(ownerId,plan,UUID.randomUUID());
        assertThat(result.amount()).isEqualByComparingTo("20.00");assertThat(cycleSnapshot()).isEqualTo(before);
        assertThat(result.upgrade().cycleId()).isEqualTo(cycle);assertThat(result.upgrade().cycleEndsAt()).isEqualTo(ends);
        assertThat(result.upgrade().fromMonthlyPrice()).isEqualByComparingTo("19.90");
        assertThat(result.upgrade().targetMonthlyPrice()).isEqualByComparingTo("39.90");
        assertThat(result.upgrade().availableVolumeLitersAfterUpgrade()).isEqualByComparingTo("220");
        assertThat(result.upgrade().availableCollectionSlotsAfterUpgrade()).isEqualTo(4);
        assertThat(owner.queryForObject("SELECT count(*) FROM payment_application WHERE establishment_id=?",Integer.class,ownerId)).isZero();
        var order=owner.queryForMap("SELECT o.purpose::text,o.target_cycle_id,o.previous_cycle_id FROM billing_order o JOIN billing_charge b ON b.billing_order_id=o.id WHERE b.id=?",result.chargeId());
        assertThat(order.get("purpose")).isEqualTo("UPGRADE");assertThat(order.get("target_cycle_id")).isEqualTo(cycle);
        assertThat(order.get("previous_cycle_id")).isNull();
    }
    @Test void confirmationReplacesLimitsAndPreservesDatesAndReservations() {
        var request=owner.queryForMap("CALL public.create_collection_request(?,?,?, ?,NULL)",ownerId,new BigDecimal("20"),"Fixture",UUID.randomUUID());
        UUID requestId=(UUID)request.get("p_collection_request_id");
        String beforeRequest=owner.queryForObject("SELECT to_jsonb(r)::text FROM collection_request r WHERE id=?",String.class,requestId);
        var result=upgrade.create(ownerId,plan,UUID.randomUUID());
        assertThat(result.upgrade().currentAvailableVolumeLiters()).isEqualByComparingTo("30");
        assertThat(result.upgrade().availableVolumeLitersAfterUpgrade()).isEqualByComparingTo("200");
        assertThat(result.upgrade().availableCollectionSlotsAfterUpgrade()).isEqualTo(3);
        confirm(result.chargeId());
        assertThat(owner.queryForObject("SELECT starts_at FROM subscription_cycle WHERE id=?",Timestamp.class,cycle).toInstant()).isEqualTo(starts);
        assertThat(owner.queryForObject("SELECT ends_at FROM subscription_cycle WHERE id=?",Timestamp.class,cycle).toInstant()).isEqualTo(ends);
        assertThat(owner.queryForObject("SELECT plan_id FROM subscription_cycle WHERE id=?",UUID.class,cycle)).isEqualTo(plan);
        assertThat(owner.queryForObject("SELECT volume_limit_liters FROM subscription_cycle WHERE id=?",BigDecimal.class,cycle)).isEqualByComparingTo("220");
        assertThat(owner.queryForObject("SELECT available_volume_liters FROM get_cycle_availability(?)",BigDecimal.class,cycle)).isEqualByComparingTo("200");
        assertThat(owner.queryForObject("SELECT to_jsonb(r)::text FROM collection_request r WHERE id=?",String.class,requestId)).isEqualTo(beforeRequest);
        assertThat(owner.queryForObject("SELECT count(*) FROM subscription_cycle WHERE establishment_id=?",Integer.class,ownerId)).isEqualTo(1);
        assertThat(owner.queryForObject("SELECT count(*) FROM subscription_cycle_change WHERE cycle_id=?",Integer.class,cycle)).isEqualTo(1);
    }
    @Test void futurePaidRenewalRemainsUnchangedAndDoesNotBecomeUpgradeTarget() {
        UUID basic=owner.queryForObject("SELECT plan_id FROM subscription_cycle WHERE id=?",UUID.class,cycle);
        var renewalQuote=renewal.create(ownerId,basic,UUID.randomUUID());confirm(renewalQuote.chargeId());
        String future=owner.queryForObject("SELECT to_jsonb(c)::text FROM subscription_cycle c WHERE subscription_id=? AND cycle_number=2",String.class,subscription);
        var result=upgrade.create(ownerId,plan,UUID.randomUUID());confirm(result.chargeId());
        assertThat(result.upgrade().cycleId()).isEqualTo(cycle);
        assertThat(owner.queryForObject("SELECT to_jsonb(c)::text FROM subscription_cycle c WHERE subscription_id=? AND cycle_number=2",String.class,subscription)).isEqualTo(future);
    }
    @Test void validityIsCappedByCycleEndAndNewLinkNeedsMoreThanThirtyFiveMinutes() {
        Instant close=Instant.now().plusSeconds(45*60).truncatedTo(ChronoUnit.SECONDS);
        owner.update("UPDATE subscription_cycle SET ends_at=? WHERE id=?",Timestamp.from(close),cycle);
        var result=upgrade.create(ownerId,plan,UUID.randomUUID());assertThat(result.expiresAt()).isEqualTo(close);
        // Link continua válido ao ser recuperado; a margem vale somente na emissão.
        owner.update("UPDATE billing_charge SET created_at=clock_timestamp()-interval '40 minutes',expires_at=date_trunc('second',clock_timestamp()+interval '20 minutes') WHERE id=?",result.chargeId());
        UUID key=owner.queryForObject("SELECT idempotency_key FROM billing_charge WHERE id=?",UUID.class,result.chargeId());
        assertThat(upgrade.create(ownerId,plan,key).chargeId()).isEqualTo(result.chargeId());
    }
    @Test void refusesNewCheckoutNearExpiryOrWithoutCurrentCycle() {
        owner.update("UPDATE subscription_cycle SET ends_at=clock_timestamp()+interval '35 minutes' WHERE id=?",cycle);
        assertThatThrownBy(()->upgrade.create(ownerId,plan,UUID.randomUUID())).isInstanceOf(BusinessRuleException.class);
        owner.update("UPDATE subscription_cycle SET ends_at=clock_timestamp()-interval '1 second' WHERE id=?",cycle);
        assertThatThrownBy(()->upgrade.create(ownerId,plan,UUID.randomUUID())).isInstanceOf(BusinessRuleException.class);
        assertThat(charges()).isZero();verifyNoInteractions(gateway);
    }
    @Test void samePlanAndNonIncreasingPriceOrEitherLimitAreRejected() {
        UUID current=owner.queryForObject("SELECT plan_id FROM subscription_cycle WHERE id=?",UUID.class,cycle);
        assertThatThrownBy(()->upgrade.create(ownerId,current,UUID.randomUUID())).isInstanceOf(BusinessRuleException.class);
        owner.update("UPDATE subscription_plan SET monthly_price=19.90 WHERE id=?",plan);
        assertThatThrownBy(()->upgrade.create(ownerId,plan,UUID.randomUUID())).isInstanceOf(BusinessRuleException.class);
        owner.update("UPDATE subscription_plan SET monthly_price=39.90,volume_limit_liters=50 WHERE id=?",plan);
        assertThatThrownBy(()->upgrade.create(ownerId,plan,UUID.randomUUID())).isInstanceOf(BusinessRuleException.class);
        owner.update("UPDATE subscription_plan SET volume_limit_liters=220,collection_limit=2 WHERE id=?",plan);
        assertThatThrownBy(()->upgrade.create(ownerId,plan,UUID.randomUUID())).isInstanceOf(BusinessRuleException.class);
        assertThat(charges()).isZero();verifyNoInteractions(gateway);
    }
    @Test void replayPreservesQuoteAndOriginUsesContractedPriceInsteadOfCatalog() {
        UUID current=owner.queryForObject("SELECT plan_id FROM subscription_cycle WHERE id=?",UUID.class,cycle);
        owner.update("UPDATE subscription_plan SET monthly_price=100 WHERE id=?",current);
        UUID key=UUID.randomUUID();var first=upgrade.create(ownerId,plan,key);
        assertThat(first.amount()).isEqualByComparingTo("20");
        owner.update("UPDATE subscription_plan SET monthly_price=99.90,volume_limit_liters=500,status='INACTIVE' WHERE id=?",plan);
        var replay=upgrade.create(ownerId,plan,key);
        assertThat(replay.chargeId()).isEqualTo(first.chargeId());assertThat(replay.amount()).isEqualByComparingTo("20");
        assertThat(replay.upgrade().targetMonthlyPrice()).isEqualByComparingTo("39.90");
        assertThat(replay.upgrade().targetVolumeLimitLiters()).isEqualByComparingTo("220");
        assertThat(charges()).isEqualTo(1);verify(gateway,times(1)).create(any());verify(gateway,times(1)).retrieve(any(),anyString());
    }
    @Test void timeoutKeepsChargeAndStableIdempotencyKey() {
        doThrow(new PaymentProviderUnavailableException()).doAnswer(invocation->external(invocation.getArgument(0))).when(gateway).create(any());
        UUID key=UUID.randomUUID();assertThatThrownBy(()->upgrade.create(ownerId,plan,key)).isInstanceOf(PaymentProviderUnavailableException.class);
        UUID id=owner.queryForObject("SELECT id FROM billing_charge WHERE idempotency_key=?",UUID.class,key);
        assertThat(upgrade.create(ownerId,plan,key).chargeId()).isEqualTo(id);assertThat(charges()).isEqualTo(1);
        assertThat(sent.get().idempotencyKey()).isEqualTo(key);
    }
    @Test void oneOpenUpgradePerCycleAndKeyCannotChangePurposeOrDestination() {
        UUID key=UUID.randomUUID();var result=upgrade.create(ownerId,plan,key);
        assertThatThrownBy(()->upgrade.create(ownerId,plan,UUID.randomUUID())).isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(()->upgrade.create(ownerId,UUID.randomUUID(),key)).isInstanceOf(ResourceConflictException.class);
        assertThatThrownBy(()->renewal.create(ownerId,plan,key)).isInstanceOf(ResourceConflictException.class);
        owner.update("UPDATE billing_charge SET status='CANCELLATION_PENDING' WHERE id=?",result.chargeId());
        assertThatThrownBy(()->upgrade.create(ownerId,plan,UUID.randomUUID())).isInstanceOf(BusinessRuleException.class);
    }
    @Test void concurrentRetriesDoNotCreateAnotherChargeOrBenefitOrder() throws Exception {
        UUID key=UUID.randomUUID();var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            Callable<UUID> job=()->{start.await();return upgrade.create(ownerId,plan,key).chargeId();};
            var a=pool.submit(job);var b=pool.submit(job);start.countDown();
            assertThat(a.get(25,TimeUnit.SECONDS)).isEqualTo(b.get(25,TimeUnit.SECONDS));
        }
        assertThat(charges()).isEqualTo(1);
        assertThat(owner.queryForObject("SELECT count(*) FROM billing_order WHERE target_cycle_id=?",Integer.class,cycle)).isEqualTo(1);
    }
    @Test void lateApplicationRequestsRefundWithoutChangingPlanOrCreatingAnotherCycle() {
        var result=upgrade.create(ownerId,plan,UUID.randomUUID());
        owner.update("UPDATE subscription_cycle SET ends_at=clock_timestamp()-interval '1 second' WHERE id=?",cycle);
        confirm(result.chargeId());
        assertThat(owner.queryForObject("SELECT monthly_price FROM subscription_cycle WHERE id=?",BigDecimal.class,cycle)).isEqualByComparingTo("19.90");
        assertThat(owner.queryForObject("SELECT count(*) FROM payment_application WHERE establishment_id=?",Integer.class,ownerId)).isZero();
        assertThat(owner.queryForObject("SELECT count(*) FROM subscription_cycle_change WHERE cycle_id=?",Integer.class,cycle)).isZero();
        var refund=owner.queryForMap("SELECT r.reason::text,r.status::text,r.amount FROM payment_refund r JOIN payment p ON p.id=r.payment_id WHERE p.billing_charge_id=?",result.chargeId());
        assertThat(refund.get("reason")).isEqualTo("EXPIRED_UPGRADE");assertThat(refund.get("status")).isEqualTo("REQUESTED");
        assertThat((BigDecimal)refund.get("amount")).isEqualByComparingTo("20");
    }
    @Test void subsequentUpgradeUsesNewContractPriceAndDistinctTransformationOrder() {
        var first=upgrade.create(ownerId,plan,UUID.randomUUID());confirm(first.chargeId());
        UUID top=UUID.randomUUID();owner.update("INSERT INTO subscription_plan(id,name,monthly_price,volume_limit_liters,collection_limit) VALUES (?,?,79.90,500,6)",top,"Top-"+top);
        var second=upgrade.create(ownerId,top,UUID.randomUUID());assertThat(second.amount()).isEqualByComparingTo("40");confirm(second.chargeId());
        assertThat(owner.queryForObject("SELECT count(*) FROM subscription_cycle_change WHERE cycle_id=?",Integer.class,cycle)).isEqualTo(2);
        assertThat(owner.queryForObject("SELECT count(DISTINCT billing_order_id) FROM billing_charge WHERE target_cycle_id=?",Integer.class,cycle)).isEqualTo(2);
        assertThat(owner.queryForObject("SELECT ends_at FROM subscription_cycle WHERE id=?",Timestamp.class,cycle).toInstant()).isEqualTo(ends);
    }
    @Test void duplicateRealPaymentForOneUpgradeDoesNotDuplicateLimitsOrHistory() {
        var result=upgrade.create(ownerId,plan,UUID.randomUUID());confirm(result.chargeId());confirm(result.chargeId());
        assertThat(owner.queryForObject("SELECT count(*) FROM subscription_cycle_change WHERE cycle_id=?",Integer.class,cycle)).isEqualTo(1);
        assertThat(owner.queryForObject("SELECT count(*) FROM payment_application WHERE establishment_id=?",Integer.class,ownerId)).isEqualTo(1);
        assertThat(owner.queryForObject("SELECT count(*) FROM payment_refund r JOIN payment p ON p.id=r.payment_id WHERE p.establishment_id=? AND r.reason='DUPLICATE_BENEFIT' AND r.status='REQUESTED'",Integer.class,ownerId)).isEqualTo(1);
    }
    @Test void inactivePlanOrOwnerCannotStartUpgrade() {
        owner.update("UPDATE subscription_plan SET status='INACTIVE' WHERE id=?",plan);
        assertThatThrownBy(()->upgrade.create(ownerId,plan,UUID.randomUUID())).isInstanceOf(ResourceNotFoundException.class);
        owner.update("UPDATE users SET status='INACTIVE' WHERE id=?",ownerId);
        assertThatThrownBy(()->upgrade.create(ownerId,plan,UUID.randomUUID())).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        assertThat(charges()).isZero();verifyNoInteractions(gateway);
    }
    @Test void endpointReturnsUpgradeTermsAndRejectsFieldsControlledByServer() throws Exception {
        var login=mvc.perform(post("/api/v1/auth/mobile/login").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("email",email,"password","Password1!")))).andExpect(status().isOk()).andReturn();
        String token=json.readTree(login.getResponse().getContentAsString()).get("accessToken").asText();
        for(String field:List.of("amount","targetCycleId","fromMonthlyPrice","expiresAt"))
            mvc.perform(post(ROUTE).header("Authorization","Bearer "+token).header("Idempotency-Key",UUID.randomUUID())
                    .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("planId",plan,field,"1"))))
                    .andExpect(status().isBadRequest());
        mvc.perform(post(ROUTE).header("Idempotency-Key",UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("planId",plan)))).andExpect(status().isUnauthorized());
        mvc.perform(post(ROUTE).header("Authorization","Bearer "+token).header("Idempotency-Key",UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("planId",plan))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.amount").value(20.00))
                .andExpect(jsonPath("$.upgrade.cycleId").value(cycle.toString()))
                .andExpect(jsonPath("$.upgrade.fromMonthlyPrice").value(19.90))
                .andExpect(jsonPath("$.upgrade.targetMonthlyPrice").value(39.90))
                .andExpect(jsonPath("$.upgrade.availabilityCalculatedAt").isNotEmpty());
    }
}
