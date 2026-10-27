package br.com.olius.olius_core_api;

import br.com.olius.olius_core_api.integration.payment.RefundFailure;
import br.com.olius.olius_core_api.integration.payment.*;
import br.com.olius.olius_core_api.integration.payment.RefundGateway.*;
import br.com.olius.olius_core_api.integration.payment.CheckoutGateway.CheckoutSession;
import br.com.olius.olius_core_api.integration.payment.PaymentVerificationGateway.VerifiedPayment;
import br.com.olius.olius_core_api.repository.PaymentEventRepository;
import br.com.olius.olius_core_api.service.*;
import br.com.olius.olius_core_api.testfixture.OfficialPostgres;
import java.math.BigDecimal;
import java.net.URI;
import java.util.*;
import java.util.concurrent.*;
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
@org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
@org.springframework.security.test.context.support.WithMockUser(roles="ADMIN")
@SpringBootTest(properties={"spring.config.import=","olius.security.ephemeral-keys=true","olius.payments.stripe.enabled=false"})
class FinancialRecoveryIntegrationTest {
    @Container static final OfficialPostgres POSTGRES=new OfficialPostgres();
    @DynamicPropertySource static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url",POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username",()->OfficialPostgres.API_USER);
        properties.add("spring.datasource.password",()->OfficialPostgres.API_PASSWORD);
    }
    @Autowired InitialCheckoutService checkout;
    @Autowired PaymentEventRepository events;
    @Autowired PaymentEventTransactions payments;
    @Autowired RefundTransactions transactions;
    @Autowired StripeRefundProcessor processor;
    @MockitoBean CheckoutGateway gateway;
    @MockitoBean RefundGateway refunds;
    @Autowired FinancialRecoveryService recovery;
    @Autowired FinancialRecoveryTransactions operational;
    @Autowired org.springframework.test.web.servlet.MockMvc mvc;
    @MockitoBean ChargeReconciliationGateway reconciliation;
    private UUID actor;
    private static final String REASON="Conferência operacional no provedor";
    private JdbcTemplate owner;
    private UUID ownerId,chargeId,refundId;
    @BeforeEach void fixture() {
        owner=new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword()));
        actor=UUID.randomUUID();
        owner.update("INSERT INTO users(id,name,email,password_hash,user_type) VALUES (?,'Admin',?,'test-hash','ADMIN')",actor,actor+"@example.test");
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

        owner.update("UPDATE payment_refund SET status='FAILED',next_attempt_at=NULL WHERE status<>'COMPLETED'");
        confirm();confirm();
        refundId=owner.queryForObject("SELECT r.id FROM payment_refund r JOIN payment p ON p.id=r.payment_id WHERE p.establishment_id=?",UUID.class,ownerId);
        when(refunds.create(any())).thenAnswer(invocation->{outside();return new Result("re_"+refundId.toString().replace("-",""),Status.SUCCEEDED,"req_fixture");});
        when(refunds.retrieve(any(),anyString())).thenAnswer(invocation->{outside();return new Result(invocation.getArgument(1),Status.SUCCEEDED,"req_fixture");});
    }
    private void outside() {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        assertThat(state()).isEqualTo("PROCESSING");
        assertThat(owner.queryForObject("SELECT count(*) FROM payment_refund_attempt WHERE refund_id=? AND finished_at IS NULL",Integer.class,refundId)).isEqualTo(1);
    }
    private void confirm() {
        String event="evt_"+UUID.randomUUID().toString().replace("-","");String payment="pi_"+UUID.randomUUID().toString().replace("-","");
        events.receive(event,payment);
        UUID id=owner.queryForObject("SELECT id FROM payment_provider_event WHERE provider_event_id=?",UUID.class,event);
        payments.complete(payments.claim(id).getFirst(),new VerifiedPayment(payment,chargeId,new BigDecimal("19.90"),"BRL",java.time.Instant.now().minusSeconds(2)));
    }
    private String state() { return owner.queryForObject("SELECT status::text FROM payment_refund WHERE id=?",String.class,refundId); }
    private int attempts() { return owner.queryForObject("SELECT count(*) FROM payment_refund_attempt WHERE refund_id=?",Integer.class,refundId); }
    private void due() { owner.update("UPDATE payment_refund SET next_attempt_at=clock_timestamp()-interval '1 second' WHERE id=?",refundId); }
    private org.springframework.test.web.servlet.request.RequestPostProcessor adminJwt() {
        return org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt()
            .jwt(jwt->jwt.subject(actor.toString())).authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_ADMIN"));
    }
    private String external(String suffix) { return "re_"+refundId.toString().replace("-","")+"_"+suffix; }
    private String body(String id) { return "{\"providerRefundId\":\""+id+"\",\"reason\":\"Conferência operacional no provedor\"}"; }
    @Test void recoversUnknownRefundAfter24HoursOnlyByConsultingExistingId() {
        doThrow(new RefundFailure(FailureKind.TEMPORARY)).when(refunds).create(any());processor.processDue();
        owner.update("UPDATE payment_refund_attempt SET started_at=clock_timestamp()-interval '25 hours' WHERE refund_id=?",refundId);
        due();processor.processDue();clearInvocations(refunds);
        var result=recovery.reconcileRefund(actor,refundId,external("found"),REASON);
        assertThat(result.status()).isEqualTo("COMPLETED");assertThat(result.providerId()).isEqualTo(external("found"));
        verify(refunds).retrieve(any(),eq(external("found")));verify(refunds,never()).create(any());
        assertThat(owner.queryForObject("SELECT count(*) FROM payment_refund_log WHERE id=? AND actor_kind='USER' AND performed_by=? AND audit_reason=?",Integer.class,refundId,actor,REASON)).isGreaterThan(0);
        assertThat(owner.queryForObject("SELECT count(*) FROM payment_application WHERE establishment_id=?",Integer.class,ownerId)).isEqualTo(1);
    }
    @Test void failedLookupPreservesRequestedRefundAndItsAuthorizedCreation() {
        String wrong=external("wrong");
        doThrow(new RefundFailure(FailureKind.TEMPORARY)).when(refunds).retrieve(any(),anyString());
        assertThatThrownBy(()->recovery.reconcileRefund(actor,refundId,wrong,REASON))
            .isInstanceOf(br.com.olius.olius_core_api.exception.PaymentProviderUnavailableException.class);
        var result=recovery.refund(actor,refundId);
        assertThat(result.status()).isEqualTo("REQUESTED");assertThat(result.providerId()).isNull();
        assertThat(result.nextAttemptAt()).isNull();assertThat(result.attempts().getFirst().result()).isEqualTo("FAILED");
        processor.processDue();assertThat(state()).isEqualTo("COMPLETED");verify(refunds,times(1)).create(any());
    }
    @Test void incompatibleLookupResultDoesNotStoreIdentityOrRemoveOriginalEligibility() {
        String found=external("found");
        doReturn(new Result(external("different"),Status.SUCCEEDED,null)).when(refunds).retrieve(any(),anyString());
        assertThatThrownBy(()->recovery.reconcileRefund(actor,refundId,found,REASON))
            .isInstanceOf(br.com.olius.olius_core_api.exception.PaymentProviderUnavailableException.class);
        var result=recovery.refund(actor,refundId);assertThat(result.status()).isEqualTo("REQUESTED");
        assertThat(result.providerId()).isNull();assertThat(result.nextAttemptAt()).isNull();
        processor.processDue();assertThat(state()).isEqualTo("COMPLETED");verify(refunds,times(1)).create(any());
    }
    @Test void failedLookupPreservesFutureAutomaticRetryAndIdempotencyKey() {
        doThrow(new RefundFailure(FailureKind.TEMPORARY)).when(refunds).create(any());processor.processDue();
        var before=recovery.refund(actor,refundId);
        UUID key=owner.queryForObject("SELECT idempotency_key FROM payment_refund WHERE id=?",UUID.class,refundId);
        String candidate=external("found");
        doThrow(new RefundFailure(FailureKind.REVIEW_REQUIRED)).when(refunds).retrieve(any(),anyString());
        assertThatThrownBy(()->recovery.reconcileRefund(actor,refundId,candidate,REASON))
            .isInstanceOf(br.com.olius.olius_core_api.exception.PaymentProviderUnavailableException.class);
        var after=recovery.refund(actor,refundId);
        assertThat(after.status()).isEqualTo(before.status());assertThat(after.nextAttemptAt()).isEqualTo(before.nextAttemptAt());
        assertThat(after.error()).isEqualTo(before.error());assertThat(after.providerId()).isNull();
        assertThat(owner.queryForObject("SELECT idempotency_key FROM payment_refund WHERE id=?",UUID.class,refundId)).isEqualTo(key);
        processor.processDue();verify(refunds,times(1)).create(any());
    }
    @Test void failedLookupDoesNotReactivateRefundAlreadyBlockedAfter24Hours() {
        doThrow(new RefundFailure(FailureKind.TEMPORARY)).when(refunds).create(any());processor.processDue();
        owner.update("UPDATE payment_refund_attempt SET started_at=clock_timestamp()-interval '25 hours' WHERE refund_id=?",refundId);
        due();processor.processDue();clearInvocations(refunds);
        String candidate=external("found");
        doThrow(new RefundFailure(FailureKind.TEMPORARY)).when(refunds).retrieve(any(),anyString());
        assertThatThrownBy(()->recovery.reconcileRefund(actor,refundId,candidate,REASON))
            .isInstanceOf(br.com.olius.olius_core_api.exception.PaymentProviderUnavailableException.class);
        var result=recovery.refund(actor,refundId);
        assertThat(result.status()).isEqualTo("FAILED");assertThat(result.nextAttemptAt()).isNull();
        assertThat(result.error()).isEqualTo("REFUND_RECONCILIATION_REQUIRED");processor.processDue();verify(refunds,never()).create(any());
    }
    @Test void failedManualLookupOfKnownRefundPreservesAutomaticPolling() {
        String known=external("known");
        doReturn(new Result(known,Status.PENDING,null)).when(refunds).create(any());processor.processDue();due();
        var before=recovery.refund(actor,refundId);
        doThrow(new RefundFailure(FailureKind.TEMPORARY)).when(refunds).retrieve(any(),anyString());
        assertThatThrownBy(()->recovery.reconcileRefund(actor,refundId,known,REASON))
            .isInstanceOf(br.com.olius.olius_core_api.exception.PaymentProviderUnavailableException.class);
        var after=recovery.refund(actor,refundId);
        assertThat(after.status()).isEqualTo("PROCESSING");assertThat(after.providerId()).isEqualTo(known);
        assertThat(after.nextAttemptAt()).isEqualTo(before.nextAttemptAt());
        doReturn(new Result(known,Status.SUCCEEDED,null)).when(refunds).retrieve(any(),anyString());processor.processDue();
        assertThat(state()).isEqualTo("COMPLETED");verify(refunds,times(1)).create(any());
    }
    @Test void refusesReplacingKnownRefundIdentity() {
        owner.update("UPDATE payment_refund SET provider_refund_id=?,status='FAILED',next_attempt_at=NULL WHERE id=?",external("known"),refundId);
        assertThatThrownBy(()->recovery.reconcileRefund(actor,refundId,external("other"),REASON)).isInstanceOf(br.com.olius.olius_core_api.exception.ResourceConflictException.class);
        verifyNoInteractions(refunds);assertThat(attempts()).isZero();
    }
    @Test void refusesCompletedRefundAndActiveWorker() {
        var claim=transactions.claim(refundId).getFirst();
        assertThatThrownBy(()->recovery.reconcileRefund(actor,refundId,external("found"),REASON)).isInstanceOf(br.com.olius.olius_core_api.exception.ResourceConflictException.class);
        transactions.complete(claim,new Result(external("found"),Status.SUCCEEDED,null));
        assertThatThrownBy(()->recovery.reconcileRefund(actor,refundId,external("found"),REASON)).isInstanceOf(br.com.olius.olius_core_api.exception.ResourceConflictException.class);
        verifyNoInteractions(refunds);
    }
    @Test void pendingLookupResumesPollingExistingId() {
        doReturn(new Result(external("pending"),Status.PENDING,null)).when(refunds).retrieve(any(),anyString());
        var result=recovery.reconcileRefund(actor,refundId,external("pending"),REASON);
        assertThat(result.status()).isEqualTo("PROCESSING");assertThat(result.completedAt()).isNull();assertThat(result.nextAttemptAt()).isNotNull();
        doReturn(new Result(external("pending"),Status.SUCCEEDED,null)).when(refunds).retrieve(any(),anyString());due();processor.processDue();
        assertThat(state()).isEqualTo("COMPLETED");verify(refunds,never()).create(any());
    }
    @Test void interruptedManualLookupWithoutSavedIdNeverTurnsIntoAnAutomaticCreation() {
        operational.prepareRefund(actor,refundId,external("found"),REASON);due();processor.processDue();
        var result=recovery.refund(actor,refundId);
        assertThat(result.status()).isEqualTo("FAILED");assertThat(result.nextAttemptAt()).isNull();
        assertThat(result.error()).isEqualTo("REFUND_RECONCILIATION_REQUIRED");
        assertThat(result.attempts().getFirst().finishedAt()).isNotNull();verifyNoInteractions(refunds);
    }
    @Test void staleManualClaimCannotReplaceNewerWorkerResult() {
        owner.update("UPDATE payment_refund SET provider_refund_id=? WHERE id=?",external("found"),refundId);
        var old=operational.prepareRefund(actor,refundId,external("found"),REASON);due();
        var current=transactions.claim(refundId).getFirst();transactions.complete(current,new Result(external("found"),Status.SUCCEEDED,null));
        assertThatThrownBy(()->operational.finishRefund(actor,REASON,old,new Result(external("found"),Status.SUCCEEDED,null)))
            .isInstanceOf(br.com.olius.olius_core_api.exception.ResourceConflictException.class);
        operational.failRefund(actor,REASON,old);
        assertThat(recovery.refund(actor,refundId).providerId()).isEqualTo(external("found"));
        assertThat(state()).isEqualTo("COMPLETED");
    }
    @Test void inactiveAdministratorCannotReadOrInitiateRecovery() {
        owner.update("UPDATE users SET status='INACTIVE' WHERE id=?",actor);
        assertThatThrownBy(()->recovery.refund(actor,refundId)).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        assertThatThrownBy(()->recovery.reconcileRefund(actor,refundId,external("found"),REASON)).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        verifyNoInteractions(refunds);
    }
    @Test void paginatedQueriesAndDetailsExposeOnlyTechnicalFields() throws Exception {
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/admin/financial-recovery/refunds").with(adminJwt()).param("size","1"))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.items.length()").value(1))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.items[0].email").doesNotExist());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/admin/financial-recovery/refunds/"+refundId).with(adminJwt()))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.id").value(refundId.toString()))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.chargeId").value(chargeId.toString()))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.providerPaymentId").value(org.hamcrest.Matchers.startsWith("pi_")));
    }
    @Test void anonymousAndEstablishmentCannotAccessOperationalEndpoints() throws Exception {
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/admin/financial-recovery/refunds")
            .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous()))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnauthorized());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/admin/financial-recovery/refunds/"+refundId+"/reconciliation")
            .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt().jwt(j->j.subject(ownerId.toString()))
                .authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_ESTABLISHMENT")))
            .contentType("application/json").content(body(external("found"))))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());
        verifyNoInteractions(refunds);
    }
    @Test void validatesReasonProviderIdPaginationAndRejectsClientStatus() throws Exception {
        for(String body:List.of(body(external("found")).replace("Conferência operacional no provedor",""),body("invalid"),body(external("found")).replace("}",",\"status\":\"COMPLETED\"}"))) {
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/admin/financial-recovery/refunds/"+refundId+"/reconciliation")
                .with(adminJwt()).contentType("application/json").content(body)).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest());
        }
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/admin/financial-recovery/refunds").with(adminJwt()).param("size","51"))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest());
        verifyNoInteractions(refunds);
    }
    @Test void httpRecoveryReturnsActualVerifiedState() throws Exception {
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/admin/financial-recovery/refunds/"+refundId+"/reconciliation")
            .with(adminJwt()).contentType("application/json").content(body(external("found"))))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.status").value("COMPLETED"));
        verify(refunds,never()).create(any());
    }
    private void reopenCharge() { owner.update("UPDATE billing_charge SET status='OPEN',closed_at=NULL WHERE id=?",chargeId); }
    @Test void chargeRecoveryNeverConfirmsPaymentByAdminDeclaration() {
        reopenCharge();
        when(reconciliation.reconcile(any())).thenAnswer(i->{
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            ChargeReconciliationGateway.Request request=i.getArgument(0);
            return new ChargeReconciliationGateway.Snapshot(request.providerId(),ChargeReconciliationGateway.State.PAID);
        });
        assertThat(recovery.reconcileCharge(actor,chargeId,REASON).status()).isEqualTo("OPEN");
    }
    @Test void uncertainChargeRemainsBlockedWithoutIdentityOverride() {
        reopenCharge();owner.update("UPDATE billing_charge SET provider_charge_id=NULL,created_at=clock_timestamp()-interval '2 hours',expires_at=clock_timestamp()-interval '1 second' WHERE id=?",chargeId);
        when(reconciliation.reconcile(any())).thenReturn(new ChargeReconciliationGateway.Snapshot(null,ChargeReconciliationGateway.State.UNCERTAIN));
        var result=recovery.reconcileCharge(actor,chargeId,REASON);
        assertThat(result.status()).isEqualTo("CANCELLATION_PENDING");assertThat(result.providerId()).isNull();assertThat(result.closedAt()).isNull();
    }
    @Test void paymentAppliedDuringAdministrativeHttpCannotBeOverwrittenByExpiration() {
        reopenCharge();
        when(reconciliation.reconcile(any())).thenAnswer(i->{
            ChargeReconciliationGateway.Request request=i.getArgument(0);
            owner.update("UPDATE billing_charge SET status='PAID',closed_at=clock_timestamp() WHERE id=?",chargeId);
            return new ChargeReconciliationGateway.Snapshot(request.providerId(),ChargeReconciliationGateway.State.EXPIRED);
        });
        assertThat(recovery.reconcileCharge(actor,chargeId,REASON).status()).isEqualTo("PAID");
    }
    @Test void chargeConfirmedExpiredUsesRestrictedRoutineAndUserAudit() {
        reopenCharge();
        when(reconciliation.reconcile(any())).thenAnswer(i->{
            ChargeReconciliationGateway.Request request=i.getArgument(0);
            return new ChargeReconciliationGateway.Snapshot(request.providerId(),ChargeReconciliationGateway.State.EXPIRED);
        });
        assertThat(recovery.reconcileCharge(actor,chargeId,REASON).status()).isEqualTo("EXPIRED");
        assertThat(owner.queryForObject("SELECT count(*) FROM billing_charge_log WHERE id=? AND actor_kind='USER' AND performed_by=?",Integer.class,chargeId,actor)).isGreaterThan(0);
    }
}
