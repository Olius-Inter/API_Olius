package br.com.olius.olius_core_api;

import br.com.olius.olius_core_api.exception.PaymentProviderUnavailableException;
import br.com.olius.olius_core_api.integration.payment.CheckoutGateway;
import br.com.olius.olius_core_api.integration.payment.CheckoutGateway.CheckoutCharge;
import br.com.olius.olius_core_api.integration.payment.CheckoutGateway.CheckoutSession;
import br.com.olius.olius_core_api.service.InitialCheckoutService;
import br.com.olius.olius_core_api.testfixture.OfficialPostgres;
import java.math.BigDecimal;
import java.net.URI;
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
        "olius.security.auth-requests-per-minute=10000","olius.payments.stripe.enabled=false"})
class InitialCheckoutIntegrationTest {
    private static final String ROUTE="/api/v1/billing/initial-checkouts";
    @Container static final OfficialPostgres POSTGRES=new OfficialPostgres();
    @DynamicPropertySource static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url",POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username",()->OfficialPostgres.API_USER);
        properties.add("spring.datasource.password",()->OfficialPostgres.API_PASSWORD);
    }
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired InitialCheckoutService service;
    @MockitoBean CheckoutGateway gateway;
    private JdbcTemplate owner;
    private UUID plan;
    private final AtomicReference<CheckoutCharge> sent=new AtomicReference<>();
    record Account(UUID id,String token) { }

    @BeforeEach void setup() {
        owner=new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword()));
        plan=UUID.randomUUID();
        owner.update("INSERT INTO subscription_plan(id,name,monthly_price,volume_limit_liters,collection_limit) VALUES (?,?,19.90,100,2)",plan,"Plano-"+plan);
        when(gateway.create(any())).thenAnswer(invocation -> external(invocation.getArgument(0)));
        when(gateway.retrieve(any(),anyString())).thenAnswer(invocation -> external(invocation.getArgument(0)));
    }
    private CheckoutSession external(CheckoutCharge charge) {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        assertThat(owner.queryForObject("SELECT count(*) FROM billing_charge WHERE id=?",Integer.class,charge.chargeId())).isEqualTo(1);
        sent.set(charge);
        return new CheckoutSession("cs_test_"+charge.chargeId().toString().replace("-",""),
                URI.create("https://checkout.stripe.com/c/pay/example"),charge.expiresAt());
    }

    @Test void createsPendingChargeAtDatabasePriceAndAuditWithoutGrantingBenefits() throws Exception {
        var account=account(false);
        UUID key=UUID.randomUUID();
        mvc.perform(post(ROUTE).header("Authorization","Bearer "+account.token()).header("Idempotency-Key",key)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("planId",plan))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.amount").value(19.90))
                .andExpect(jsonPath("$.currency").value("BRL")).andExpect(jsonPath("$.planId").value(plan.toString()))
                .andExpect(jsonPath("$.paymentUrl").value("https://checkout.stripe.com/c/pay/example"));
        assertThat(sent.get().idempotencyKey()).isEqualTo(key);
        assertThat(owner.queryForObject("SELECT status::text FROM establishment_subscription WHERE establishment_id=?",String.class,account.id())).isEqualTo("PENDING");
        assertThat(owner.queryForObject("SELECT status::text FROM billing_charge WHERE id=?",String.class,sent.get().chargeId())).isEqualTo("OPEN");
        assertThat(owner.queryForObject("SELECT EXTRACT(EPOCH FROM expires_at-created_at)::double precision FROM billing_charge WHERE id=?",Double.class,sent.get().chargeId())).isBetween(3598.0,3602.0);
        assertThat(owner.queryForObject("SELECT count(*) FROM subscription_cycle WHERE establishment_id=?",Integer.class,account.id())).isZero();
        assertThat(owner.queryForObject("SELECT count(*) FROM payment WHERE establishment_id=?",Integer.class,account.id())).isZero();
        assertThat(owner.queryForObject("SELECT count(*) FROM billing_charge_log WHERE id=? AND performed_by=? AND actor_kind='USER'",Integer.class,sent.get().chargeId(),account.id())).isEqualTo(3);
    }

    @Test void replayRetrievesExistingSessionAndPreservesQuoteAfterCatalogChange() throws Exception {
        var account=account(false); UUID key=UUID.randomUUID();
        var first=service.create(account.id(),plan,key);
        owner.update("UPDATE subscription_plan SET name='Alterado',monthly_price=99.90,status='INACTIVE' WHERE id=?",plan);
        var second=service.create(account.id(),plan,key);
        assertThat(second).isEqualTo(first);
        assertThat(sent.get().amount()).isEqualByComparingTo("19.90");
        assertThat(sent.get().planName()).isEqualTo("Plano-"+plan);
        verify(gateway,times(1)).create(any()); verify(gateway,times(1)).retrieve(any(),anyString());
        assertThat(countCharges(account.id())).isEqualTo(1);
    }

    @Test void timeoutKeepsCommittedChargeAndRetryUsesSameKey() throws Exception {
        var account=account(false); UUID key=UUID.randomUUID();
        doThrow(new PaymentProviderUnavailableException())
                .doAnswer(invocation -> external(invocation.getArgument(0))).when(gateway).create(any());
        mvc.perform(post(ROUTE).header("Authorization","Bearer "+account.token()).header("Idempotency-Key",key)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("planId",plan))))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("SERVICE_UNAVAILABLE"));
        UUID original=owner.queryForObject("SELECT id FROM billing_charge WHERE idempotency_key=?",UUID.class,key);
        assertThat(owner.queryForObject("SELECT provider_charge_id FROM billing_charge WHERE id=?",String.class,original)).isNull();
        var response=service.create(account.id(),plan,key);
        assertThat(response.chargeId()).isEqualTo(original);
        assertThat(sent.get().idempotencyKey()).isEqualTo(key);
        assertThat(countCharges(account.id())).isEqualTo(1);
    }

    @Test void anonymousCitizenAndInactiveAccountCannotCreateCharges() throws Exception {
        mvc.perform(post(ROUTE).header("Idempotency-Key",UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("planId",plan))))
                .andExpect(status().isUnauthorized());
        var citizen=account(true);
        mvc.perform(post(ROUTE).header("Authorization","Bearer "+citizen.token()).header("Idempotency-Key",UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("planId",plan))))
                .andExpect(status().isForbidden());
        var inactive=account(false);
        owner.update("UPDATE users SET status='INACTIVE' WHERE id=?",inactive.id());
        mvc.perform(post(ROUTE).header("Authorization","Bearer "+inactive.token()).header("Idempotency-Key",UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("planId",plan))))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(gateway);
        assertThat(countCharges(inactive.id())).isZero();
    }

    @Test void refusesClientControlledPriceOwnerAndUnknownFields() throws Exception {
        var account=account(false);
        for(String field:List.of("amount","establishmentId","status","expiresAt")) {
            mvc.perform(post(ROUTE).header("Authorization","Bearer "+account.token()).header("Idempotency-Key",UUID.randomUUID())
                    .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("planId",plan,field,"1"))))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(post(ROUTE).header("Authorization","Bearer "+account.token())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("planId",plan))))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(gateway);
    }

    @Test void inactiveOrMissingPlanDoesNotLeavePendingSubscription() throws Exception {
        var account=account(false);
        owner.update("UPDATE subscription_plan SET status='INACTIVE' WHERE id=?",plan);
        mvc.perform(post(ROUTE).header("Authorization","Bearer "+account.token()).header("Idempotency-Key",UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("planId",plan))))
                .andExpect(status().isNotFound());
        assertThat(owner.queryForObject("SELECT count(*) FROM establishment_subscription WHERE establishment_id=?",Integer.class,account.id())).isZero();
        verifyNoInteractions(gateway);
    }

    @Test void anotherKeyCannotCreateSecondOpenChargeAndKeyCannotCrossOwnersOrPlans() throws Exception {
        var account=account(false); UUID key=UUID.randomUUID();
        service.create(account.id(),plan,key);
        assertThatThrownBy(()->service.create(account.id(),plan,UUID.randomUUID()))
                .isInstanceOf(br.com.olius.olius_core_api.exception.BusinessRuleException.class);
        var other=account(false);
        assertThatThrownBy(()->service.create(other.id(),plan,key))
                .isInstanceOf(br.com.olius.olius_core_api.exception.ResourceConflictException.class);
        assertThatThrownBy(()->service.create(account.id(),UUID.randomUUID(),key))
                .isInstanceOf(br.com.olius.olius_core_api.exception.ResourceConflictException.class);
        assertThat(countCharges(account.id())).isEqualTo(1);
        assertThat(countCharges(other.id())).isZero();
    }

    @Test void simultaneousRetriesHaveOneChargeOrderAndSubscription() throws Exception {
        var account=account(false); UUID key=UUID.randomUUID();
        var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            Callable<UUID> task=()-> { start.await(); return service.create(account.id(),plan,key).chargeId(); };
            var first=pool.submit(task);var second=pool.submit(task);start.countDown();
            assertThat(first.get(20,TimeUnit.SECONDS)).isEqualTo(second.get(20,TimeUnit.SECONDS));
        }
        assertThat(countCharges(account.id())).isEqualTo(1);
        assertThat(owner.queryForObject("SELECT count(*) FROM billing_order WHERE establishment_id=?",Integer.class,account.id())).isEqualTo(1);
        assertThat(owner.queryForObject("SELECT count(*) FROM establishment_subscription WHERE establishment_id=?",Integer.class,account.id())).isEqualTo(1);
    }

    @Test void expiredLocalChargeIsNotSilentlyReissuedOrMarkedPaid() throws Exception {
        var account=account(false); UUID key=UUID.randomUUID();
        var original=service.create(account.id(),plan,key);
        owner.update("""
                UPDATE billing_charge SET created_at=clock_timestamp()-interval '2 hours',
                expires_at=clock_timestamp()-interval '1 hour' WHERE id=?
                """,original.chargeId());
        clearInvocations(gateway);
        assertThatThrownBy(()->service.create(account.id(),plan,key))
                .isInstanceOf(br.com.olius.olius_core_api.exception.BusinessRuleException.class);
        verifyNoInteractions(gateway);
        assertThat(countCharges(account.id())).isEqualTo(1);
        assertThat(owner.queryForObject("SELECT status::text FROM billing_charge WHERE id=?",String.class,original.chargeId())).isEqualTo("OPEN");
    }

    @Test void establishmentWithPaidCycleMustUseFutureRenewalFlow() throws Exception {
        var account=account(false);
        UUID subscription=UUID.randomUUID();
        owner.update("INSERT INTO establishment_subscription(id,establishment_id) VALUES (?,?)",subscription,account.id());
        owner.update("""
                INSERT INTO subscription_cycle(id,subscription_id,establishment_id,cycle_number,
                starts_at,ends_at,anchor_day,anchor_local_time,anchor_timezone,
                plan_id,plan_name,monthly_price,volume_limit_liters,collection_limit)
                VALUES (?,?,?,1,clock_timestamp(),clock_timestamp()+interval '1 month',
                1,'12:00:00','America/Sao_Paulo',?,'Plano',19.90,100,2)
                """,UUID.randomUUID(),subscription,account.id(),plan);
        assertThatThrownBy(()->service.create(account.id(),plan,UUID.randomUUID()))
                .isInstanceOf(br.com.olius.olius_core_api.exception.BusinessRuleException.class);
        assertThat(countCharges(account.id())).isZero();
        verifyNoInteractions(gateway);
    }

    private int countCharges(UUID id) { return owner.queryForObject("SELECT count(*) FROM billing_charge WHERE establishment_id=?",Integer.class,id); }
    private Account account(boolean citizen) throws Exception {
        String email=UUID.randomUUID()+"@example.test";
        var body=new LinkedHashMap<String,Object>();
        body.put("name","Teste");body.put("email",email);body.put("password","Password1!");
        body.put("telephones",List.of("11999999999"));
        if(citizen) body.put("cpf",digits(11));
        else {
            UUID type=UUID.randomUUID();
            owner.update("INSERT INTO establishment_type(id,name) VALUES (?,?)",type,"Tipo-"+type);
            body.put("cnpj",digits(14));body.put("typeId",type);
            body.put("address",Map.of("state","SP","city","Cidade","neighborhood","Bairro","street","Rua","number","1","cep","00000000"));
        }
        var registration=mvc.perform(post("/api/v1/auth/registrations/"+(citizen?"citizens":"establishments"))
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
                .andExpect(status().isCreated()).andReturn();
        UUID id=UUID.fromString(json.readTree(registration.getResponse().getContentAsString()).get("id").asText());
        var login=mvc.perform(post("/api/v1/auth/mobile/login").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("email",email,"password","Password1!"))))
                .andExpect(status().isOk()).andReturn();
        return new Account(id,json.readTree(login.getResponse().getContentAsString()).get("accessToken").asText());
    }
    private String digits(int size) { return ""+ThreadLocalRandom.current().nextLong((long)Math.pow(10,size-1),(long)Math.pow(10,size)); }
}
