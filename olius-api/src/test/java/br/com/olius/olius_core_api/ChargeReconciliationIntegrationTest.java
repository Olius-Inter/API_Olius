package br.com.olius.olius_core_api;

import br.com.olius.olius_core_api.integration.payment.*;
import br.com.olius.olius_core_api.integration.payment.CheckoutGateway.CheckoutSession;
import br.com.olius.olius_core_api.integration.payment.ChargeReconciliationGateway.*;
import br.com.olius.olius_core_api.integration.payment.PaymentVerificationGateway.VerifiedPayment;
import br.com.olius.olius_core_api.repository.PaymentEventRepository;
import br.com.olius.olius_core_api.service.*;
import br.com.olius.olius_core_api.testfixture.OfficialPostgres;
import java.math.BigDecimal;
import java.net.URI;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@Testcontainers
@SpringBootTest(properties={"spring.config.import=","olius.security.ephemeral-keys=true","olius.payments.stripe.enabled=false"})
class ChargeReconciliationIntegrationTest {
    @Container static final OfficialPostgres POSTGRES=new OfficialPostgres();
    @DynamicPropertySource static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url",POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username",()->OfficialPostgres.API_USER);
        properties.add("spring.datasource.password",()->OfficialPostgres.API_PASSWORD);
    }
    @Autowired InitialCheckoutService checkout;
    @Autowired ChargeReconciliationTransactions transactions;
    @Autowired StripeChargeReconciler reconciler;
    @Autowired PaymentEventRepository events;
    @Autowired PaymentEventTransactions payments;
    @Autowired StripePaymentEventProcessor paymentProcessor;
    @MockitoBean CheckoutGateway gateway;
    @MockitoBean ChargeReconciliationGateway reconciliation;
    @MockitoBean PaymentVerificationGateway verification;
    private JdbcTemplate owner;
    private UUID ownerId,chargeId;
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


        owner.update("UPDATE billing_charge SET status='EXPIRED',closed_at=clock_timestamp() WHERE id<>? AND status IN ('OPEN','CANCELLATION_PENDING')",chargeId);
        when(reconciliation.events(any(),any(),any())).thenReturn(new Page(List.of(),null));
        when(reconciliation.reconcile(any())).thenAnswer(invocation->{
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            Request request=invocation.getArgument(0);boolean expired=!request.expiresAt().isAfter(java.time.Instant.now());
            assertThat(state()).isEqualTo(expired ? "CANCELLATION_PENDING" : "OPEN");
            return new Snapshot(request.providerId(),expired ? State.EXPIRED : State.OPEN);
        });
    }
    private String state() { return owner.queryForObject("SELECT status::text FROM billing_charge WHERE id=?",String.class,chargeId); }
    private void expire() { owner.update("UPDATE billing_charge SET created_at=clock_timestamp()-interval '2 hours',expires_at=clock_timestamp()-interval '1 second' WHERE id=?",chargeId); }
    private void confirm() {
        String event="evt_"+UUID.randomUUID().toString().replace("-","");String payment="pi_"+UUID.randomUUID().toString().replace("-","");
        events.receive(event,payment);UUID id=owner.queryForObject("SELECT id FROM payment_provider_event WHERE provider_event_id=?",UUID.class,event);
        payments.complete(payments.claim(id).getFirst(),new VerifiedPayment(payment,chargeId,new BigDecimal("19.90"),"BRL",java.time.Instant.now().minusSeconds(2)));
    }
    @Test void onlyConfirmedExpirationClosesTheChargeUsingTheRestrictedRole() {
        expire();reconciler.processDue();assertThat(state()).isEqualTo("EXPIRED");
        assertThat(owner.queryForObject("SELECT closed_at FROM billing_charge WHERE id=?",java.sql.Timestamp.class,chargeId)).isNotNull();
        assertThat(owner.queryForObject("SELECT count(*) FROM billing_charge_log WHERE id=? AND actor_kind='SYSTEM'",Integer.class,chargeId)).isGreaterThan(0);
    }
    @Test void validCheckoutIsInspectedWithoutPrematureExpirationOrBenefit() {
        reconciler.processDue();assertThat(state()).isEqualTo("OPEN");verify(reconciliation).reconcile(any());
        assertThat(owner.queryForObject("SELECT count(*) FROM subscription_cycle WHERE establishment_id=?",Integer.class,ownerId)).isZero();
    }
    @Test void expirationConfirmedByStripeCanBeSynchronizedBeforeTheOriginalDeadline() {
        String id=owner.queryForObject("SELECT provider_charge_id FROM billing_charge WHERE id=?",String.class,chargeId);
        doReturn(new Snapshot(id,State.EXPIRED)).when(reconciliation).reconcile(any());
        reconciler.processDue();assertThat(state()).isEqualTo("EXPIRED");
    }
    @Test void uncertainIdentityDoesNotReleaseTheOpenChargeSlot() {
        expire();owner.update("UPDATE billing_charge SET provider_charge_id=NULL WHERE id=?",chargeId);
        doReturn(new Snapshot(null,State.UNCERTAIN)).when(reconciliation).reconcile(any());reconciler.processDue();
        assertThat(state()).isEqualTo("CANCELLATION_PENDING");
        UUID plan=owner.queryForObject("SELECT plan_id FROM billing_charge WHERE id=?",UUID.class,chargeId);
        assertThatThrownBy(()->checkout.create(ownerId,plan,UUID.randomUUID())).isInstanceOf(br.com.olius.olius_core_api.exception.BusinessRuleException.class);
    }
    @Test void recoversMissingSessionIdentityBeforeExpiringIt() {
        expire();owner.update("UPDATE billing_charge SET provider_charge_id=NULL WHERE id=?",chargeId);
        doReturn(new Snapshot("cs_test_recovered",State.EXPIRED)).when(reconciliation).reconcile(any());reconciler.processDue();
        assertThat(state()).isEqualTo("EXPIRED");
        assertThat(owner.queryForObject("SELECT provider_charge_id FROM billing_charge WHERE id=?",String.class,chargeId)).isEqualTo("cs_test_recovered");
    }
    @Test void providerFailureKeepsCancellationPendingAndCanBeRetried() {
        expire();doThrow(new br.com.olius.olius_core_api.exception.PaymentProviderUnavailableException()).when(reconciliation).reconcile(any());
        reconciler.processDue();assertThat(state()).isEqualTo("CANCELLATION_PENDING");
        String id=owner.queryForObject("SELECT provider_charge_id FROM billing_charge WHERE id=?",String.class,chargeId);
        doReturn(new Snapshot(id,State.EXPIRED)).when(reconciliation).reconcile(any());reconciler.processDue();assertThat(state()).isEqualTo("EXPIRED");
    }
    @Test void paymentAppliedDuringExternalInspectionCannotBeOverwritten() {
        expire();var request=transactions.prepare(chargeId).getFirst();confirm();assertThat(state()).isEqualTo("PAID");
        transactions.finish(request,new Snapshot(request.providerId(),State.EXPIRED));assertThat(state()).isEqualTo("PAID");
    }
    @Test void paidSessionWaitsForVerifiedPaymentInsteadOfBeingMarkedPaidDirectly() {
        expire();String id=owner.queryForObject("SELECT provider_charge_id FROM billing_charge WHERE id=?",String.class,chargeId);
        doReturn(new Snapshot(id,State.PAID)).when(reconciliation).reconcile(any());reconciler.processDue();
        assertThat(state()).isEqualTo("CANCELLATION_PENDING");assertThat(owner.queryForObject("SELECT count(*) FROM payment WHERE establishment_id=?",Integer.class,ownerId)).isZero();
    }
    @Test void missingWebhookIsRecoveredIntoTheSameDurablePaymentPipeline() {
        String event="evt_"+UUID.randomUUID().toString().replace("-","");String payment="pi_"+UUID.randomUUID().toString().replace("-","");
        doReturn(new Page(List.of(new Notice(event,payment,chargeId),new Notice("evt_unrelated","pi_other",UUID.randomUUID())),null)).when(reconciliation).events(any(),any(),any());
        when(verification.verify(event,payment)).thenReturn(new VerifiedPayment(payment,chargeId,new BigDecimal("19.90"),"BRL",java.time.Instant.now().minusSeconds(2)));
        reconciler.processDue();reconciler.processDue();
        assertThat(owner.queryForObject("SELECT count(*) FROM payment_provider_event WHERE provider_event_id=?",Integer.class,event)).isEqualTo(1);
        assertThat(owner.queryForObject("SELECT count(*) FROM payment_provider_event WHERE provider_event_id='evt_unrelated'",Integer.class)).isZero();
        paymentProcessor.processDue();assertThat(state()).isEqualTo("PAID");
        assertThat(owner.queryForObject("SELECT count(*) FROM subscription_cycle WHERE establishment_id=?",Integer.class,ownerId)).isEqualTo(1);
    }
    @Test void confirmedExpirationAllowsANewAttemptWithoutAnotherBenefitOrder() {
        expire();reconciler.processDue();UUID plan=owner.queryForObject("SELECT plan_id FROM billing_charge WHERE id=?",UUID.class,chargeId);
        UUID next=checkout.create(ownerId,plan,UUID.randomUUID()).chargeId();assertThat(next).isNotEqualTo(chargeId);
        assertThat(owner.queryForObject("SELECT count(*) FROM billing_order WHERE establishment_id=?",Integer.class,ownerId)).isEqualTo(1);
    }
}
