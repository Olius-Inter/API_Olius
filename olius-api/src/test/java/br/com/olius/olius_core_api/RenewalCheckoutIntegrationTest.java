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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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
class RenewalCheckoutIntegrationTest {
    private static final String ROUTE="/api/v1/billing/renewal-checkouts";
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
    @Autowired InitialCheckoutService initial;
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
        owner.update("INSERT INTO subscription_plan(id,name,monthly_price,volume_limit_liters,collection_limit) VALUES (?,?,39.90,220,4)",oldPlan,"Atual-"+oldPlan);
        owner.update("INSERT INTO subscription_plan(id,name,monthly_price,volume_limit_liters,collection_limit) VALUES (?,?,19.90,50,2)",plan,"Próximo-"+plan);
        var start=ZonedDateTime.now(ZoneId.of("America/Sao_Paulo")).minusDays(1).truncatedTo(ChronoUnit.SECONDS);
        starts=start.toInstant();ends=start.plusMonths(1).toInstant();
        owner.update("INSERT INTO establishment_subscription(id,establishment_id,status,activated_at,created_at) VALUES (?,?,'ACTIVE',?,?)",subscription,ownerId,Timestamp.from(starts),Timestamp.from(starts));
        owner.update("""
                INSERT INTO subscription_cycle(id,subscription_id,establishment_id,cycle_number,starts_at,ends_at,
                anchor_day,anchor_local_time,anchor_timezone,plan_id,plan_name,monthly_price,volume_limit_liters,collection_limit)
                VALUES (?,?,?,1,?,?,?,?, 'America/Sao_Paulo',?,'Plano atual',39.90,220,4)
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
                URI.create("https://checkout.stripe.com/c/pay/renewal"),charge.expiresAt());
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

    @Test void issueFreezesQuoteReferencesLatestCycleAndDoesNotChangeCurrentPeriod() {
        String before=cycleSnapshot();UUID key=UUID.randomUUID();var result=renewal.create(ownerId,plan,key);
        assertThat(result.amount()).isEqualByComparingTo("19.90");assertThat(cycleSnapshot()).isEqualTo(before);
        assertThat(owner.queryForObject("SELECT count(*) FROM subscription_cycle WHERE establishment_id=?",Integer.class,ownerId)).isEqualTo(1);
        var order=owner.queryForMap("SELECT o.purpose::text,o.previous_cycle_id,o.target_cycle_id,o.subscription_id FROM billing_order o JOIN billing_charge c ON c.billing_order_id=o.id WHERE c.id=?",result.chargeId());
        assertThat(order.get("purpose")).isEqualTo("RENEWAL");assertThat(order.get("previous_cycle_id")).isEqualTo(cycle);
        assertThat(order.get("target_cycle_id")).isNull();assertThat(order.get("subscription_id")).isEqualTo(subscription);
        assertThat(owner.queryForObject("SELECT EXTRACT(EPOCH FROM expires_at-created_at)::double precision FROM billing_charge WHERE id=?",Double.class,result.chargeId())).isBetween(3598.0,3602.0);
    }
    @Test void advancePaymentStartsAtCurrentEndAndDowngradeOnlyAffectsNextCycle() {
        String before=cycleSnapshot();var result=renewal.create(ownerId,plan,UUID.randomUUID());confirm(result.chargeId());
        assertThat(cycleSnapshot()).isEqualTo(before);
        var next=owner.queryForMap("SELECT starts_at,ends_at,monthly_price,volume_limit_liters,collection_limit FROM subscription_cycle WHERE subscription_id=? AND cycle_number=2",subscription);
        assertThat(((Timestamp)next.get("starts_at")).toInstant()).isEqualTo(ends);
        assertThat(((Timestamp)next.get("ends_at")).toInstant()).isEqualTo(ends.atZone(ZoneId.of("America/Sao_Paulo")).plusMonths(1).toInstant());
        assertThat((BigDecimal)next.get("monthly_price")).isEqualByComparingTo("19.90");
        assertThat((BigDecimal)next.get("volume_limit_liters")).isEqualByComparingTo("50");
        assertThat(next.get("collection_limit")).isEqualTo(2);
    }
    @Test void latePaymentBeginsAtReleaseWithoutRetroactivePeriod() {
        var old=ZonedDateTime.now(ZoneId.of("America/Sao_Paulo")).minusMonths(2).truncatedTo(ChronoUnit.SECONDS);
        owner.update("UPDATE subscription_cycle SET starts_at=?,ends_at=?,anchor_day=?,anchor_local_time=? WHERE id=?",
                Timestamp.from(old.toInstant()),Timestamp.from(old.plusMonths(1).toInstant()),old.getDayOfMonth(),java.sql.Time.valueOf(old.toLocalTime()),cycle);
        String before=cycleSnapshot();var result=renewal.create(ownerId,plan,UUID.randomUUID());Instant earliest=Instant.now();confirm(result.chargeId());
        Instant newStart=owner.queryForObject("SELECT starts_at FROM subscription_cycle WHERE subscription_id=? AND cycle_number=2",Timestamp.class,subscription).toInstant();
        assertThat(newStart).isAfterOrEqualTo(earliest).isBeforeOrEqualTo(Instant.now());assertThat(cycleSnapshot()).isEqualTo(before);
    }
    @Test void cycleMayExpireWhileCheckoutRemainsValidAndPaymentStillStartsAtRelease() {
        owner.update("UPDATE subscription_cycle SET ends_at=clock_timestamp()+interval '1 minute' WHERE id=?",cycle);
        var result=renewal.create(ownerId,plan,UUID.randomUUID());
        Instant previousEnd=owner.queryForObject("SELECT ends_at FROM subscription_cycle WHERE id=?",Timestamp.class,cycle).toInstant();
        assertThat(result.expiresAt()).isAfter(previousEnd);
        owner.update("UPDATE subscription_cycle SET ends_at=clock_timestamp()-interval '1 second' WHERE id=?",cycle);
        Instant earliest=Instant.now();confirm(result.chargeId());
        Instant newStart=owner.queryForObject("SELECT starts_at FROM subscription_cycle WHERE subscription_id=? AND cycle_number=2",Timestamp.class,subscription).toInstant();
        assertThat(newStart).isAfterOrEqualTo(earliest);
    }
    @Test void calendarAnchorThirtyOneIsPreservedAcrossShorterMonths() {
        ZoneId zone=ZoneId.of("America/Sao_Paulo");
        var endMonth=YearMonth.now(zone);
        var end=endMonth.atEndOfMonth().atTime(12,0).atZone(zone);
        if(!end.toInstant().isAfter(Instant.now())) {
            endMonth=endMonth.plusMonths(1);
            end=endMonth.atEndOfMonth().atTime(12,0).atZone(zone);
        }
        var begin=endMonth.minusMonths(1).atEndOfMonth().atTime(12,0).atZone(zone);
        var expectedEnd=endMonth.plusMonths(1).atEndOfMonth().atTime(12,0).atZone(zone);
        owner.update("UPDATE subscription_cycle SET starts_at=?,ends_at=?,anchor_day=31,anchor_local_time='12:00:00' WHERE id=?",
                Timestamp.from(begin.toInstant()),Timestamp.from(end.toInstant()),cycle);
        var result=renewal.create(ownerId,plan,UUID.randomUUID());confirm(result.chargeId());
        var next=owner.queryForMap("SELECT starts_at,ends_at,anchor_day FROM subscription_cycle WHERE subscription_id=? AND cycle_number=2",subscription);
        assertThat(((Timestamp)next.get("starts_at")).toInstant()).isEqualTo(end.toInstant());
        assertThat(((Timestamp)next.get("ends_at")).toInstant()).isEqualTo(expectedEnd.toInstant());
        assertThat(((Number)next.get("anchor_day")).intValue()).isEqualTo(31);
    }

    @ParameterizedTest(name="anchor {0}: {1} -> {2}")
    @CsvSource({
        "29,2033-01-29,2033-02-28", "30,2033-01-30,2033-02-28", "31,2033-01-31,2033-02-28",
        "29,2032-01-29,2032-02-29", "30,2032-01-30,2032-02-29", "31,2032-01-31,2032-02-29",
        "29,2033-02-28,2033-03-29", "30,2033-02-28,2033-03-30", "31,2033-02-28,2033-03-31",
        "31,2033-04-30,2033-05-31"
    })
    void calendarPreservesCivilAnchorThroughFebruaryAndThirtyDayMonths(int anchor,String endDate,String expectedDate) {
        ZoneId zone=ZoneId.of("America/Sao_Paulo");
        Instant end=LocalDate.parse(endDate).atTime(12,34,56).atZone(zone).toInstant();
        // Only the disposable fixture spans a longer period: the database clock is not changed.
        owner.update("UPDATE subscription_cycle SET ends_at=?,anchor_day=?,anchor_local_time='12:34:56' WHERE id=?",
                Timestamp.from(end),anchor,cycle);
        String before=cycleSnapshot();
        var checkout=renewal.create(ownerId,plan,UUID.randomUUID());confirm(checkout.chargeId());
        var next=owner.queryForMap("SELECT starts_at,ends_at,anchor_day,anchor_timezone FROM subscription_cycle WHERE subscription_id=? AND cycle_number=2",subscription);
        assertThat(((Timestamp)next.get("starts_at")).toInstant()).isEqualTo(end);
        assertThat(((Timestamp)next.get("ends_at")).toInstant()).isEqualTo(LocalDate.parse(expectedDate).atTime(12,34,56).atZone(zone).toInstant());
        assertThat(((Number)next.get("anchor_day")).intValue()).isEqualTo(anchor);
        assertThat(next.get("anchor_timezone")).isEqualTo("America/Sao_Paulo");
        assertThat(cycleSnapshot()).isEqualTo(before);
    }

    @Test void alreadyPaidNextCycleCannotOriginateAnotherAdvanceRenewal() {
        var first=renewal.create(ownerId,plan,UUID.randomUUID());confirm(first.chargeId());clearInvocations(gateway);
        assertThatThrownBy(()->renewal.create(ownerId,plan,UUID.randomUUID())).isInstanceOf(BusinessRuleException.class);
        assertThat(charges()).isEqualTo(1);verifyNoInteractions(gateway);
    }
    @Test void replayKeepsOriginalQuoteAndDoesNotIssueAnotherSession() {
        UUID key=UUID.randomUUID();var first=renewal.create(ownerId,plan,key);
        owner.update("UPDATE subscription_plan SET monthly_price=99.90,status='INACTIVE' WHERE id=?",plan);
        assertThat(renewal.create(ownerId,plan,key)).isEqualTo(first);
        assertThat(sent.get().amount()).isEqualByComparingTo("19.90");assertThat(charges()).isEqualTo(1);
        verify(gateway,times(1)).create(any());verify(gateway,times(1)).retrieve(any(),anyString());
    }
    @Test void timeoutDoesNotLoseLocalChargeOrGenerateAnotherKey() {
        doThrow(new PaymentProviderUnavailableException()).doAnswer(invocation->external(invocation.getArgument(0))).when(gateway).create(any());
        UUID key=UUID.randomUUID();assertThatThrownBy(()->renewal.create(ownerId,plan,key)).isInstanceOf(PaymentProviderUnavailableException.class);
        UUID original=owner.queryForObject("SELECT id FROM billing_charge WHERE idempotency_key=?",UUID.class,key);
        assertThat(renewal.create(ownerId,plan,key).chargeId()).isEqualTo(original);assertThat(charges()).isEqualTo(1);
        assertThat(sent.get().idempotencyKey()).isEqualTo(key);
    }
    @Test void onlyOneOpenPeriodChargeAndKeyCannotChangePurposeOrPlan() {
        UUID key=UUID.randomUUID();var first=renewal.create(ownerId,plan,key);
        assertThatThrownBy(()->renewal.create(ownerId,plan,UUID.randomUUID())).isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(()->renewal.create(ownerId,UUID.randomUUID(),key)).isInstanceOf(ResourceConflictException.class);
        assertThatThrownBy(()->initial.create(ownerId,plan,key)).isInstanceOf(ResourceConflictException.class);
        owner.update("UPDATE billing_charge SET status='CANCELLATION_PENDING' WHERE id=?",first.chargeId());
        assertThatThrownBy(()->renewal.create(ownerId,plan,UUID.randomUUID())).isInstanceOf(BusinessRuleException.class);
        assertThat(charges()).isEqualTo(1);
    }
    @Test void concurrentRetriesPreserveOneRenewalOrder() throws Exception {
        UUID key=UUID.randomUUID();var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            Callable<UUID> task=()->{start.await();return renewal.create(ownerId,plan,key).chargeId();};
            var a=pool.submit(task);var b=pool.submit(task);start.countDown();
            assertThat(a.get(25,TimeUnit.SECONDS)).isEqualTo(b.get(25,TimeUnit.SECONDS));
        }
        assertThat(charges()).isEqualTo(1);
        assertThat(owner.queryForObject("SELECT count(*) FROM billing_order WHERE previous_cycle_id=?",Integer.class,cycle)).isEqualTo(1);
    }
    @Test void noPreviousCycleCannotRenewAndInactivePlanCannotIssueNewQuote() {
        assertThatThrownBy(()->renewal.create(ownerId,UUID.randomUUID(),UUID.randomUUID())).isInstanceOf(ResourceNotFoundException.class);
        owner.update("UPDATE subscription_plan SET status='INACTIVE' WHERE id=?",plan);
        assertThatThrownBy(()->renewal.create(ownerId,plan,UUID.randomUUID())).isInstanceOf(ResourceNotFoundException.class);
        owner.update("DELETE FROM subscription_cycle WHERE id=?",cycle);
        assertThatThrownBy(()->renewal.create(ownerId,plan,UUID.randomUUID())).isInstanceOf(BusinessRuleException.class);
        assertThat(charges()).isZero();verifyNoInteractions(gateway);
    }
    @Test void inactiveAccountCannotPrepareRenewal() {
        owner.update("UPDATE users SET status='INACTIVE' WHERE id=?",ownerId);
        assertThatThrownBy(()->renewal.create(ownerId,plan,UUID.randomUUID())).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        assertThat(charges()).isZero();verifyNoInteractions(gateway);
    }
    @Test void endpointUsesLoggedInOwnerAndRejectsClientControlledFields() throws Exception {
        var login=mvc.perform(post("/api/v1/auth/mobile/login").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("email",email,"password","Password1!")))).andExpect(status().isOk()).andReturn();
        String token=json.readTree(login.getResponse().getContentAsString()).get("accessToken").asText();
        for(String field:List.of("amount","previousCycleId","establishmentId","status")) {
            mvc.perform(post(ROUTE).header("Authorization","Bearer "+token).header("Idempotency-Key",UUID.randomUUID())
                    .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("planId",plan,field,"1"))))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(post(ROUTE).header("Idempotency-Key",UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("planId",plan)))).andExpect(status().isUnauthorized());
        mvc.perform(post(ROUTE).header("Authorization","Bearer "+token).header("Idempotency-Key",UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("planId",plan))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.amount").value(19.90));
    }
}
