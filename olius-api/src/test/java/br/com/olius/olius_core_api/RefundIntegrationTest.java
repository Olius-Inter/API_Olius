package br.com.olius.olius_core_api;

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
@SpringBootTest(properties={"spring.config.import=","olius.security.ephemeral-keys=true","olius.payments.stripe.enabled=false"})
class RefundIntegrationTest {
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
    private JdbcTemplate owner;
    private UUID ownerId,chargeId,refundId;
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
    @Test void completesOnlyTheRequestedDuplicateRefundWithoutChangingBenefits() {
        assertThat(state()).isEqualTo("REQUESTED");
        assertThat(owner.queryForObject("SELECT completed_at FROM payment_refund WHERE id=?",java.sql.Timestamp.class,refundId)).isNull();
        processor.processDue();processor.processDue();
        assertThat(state()).isEqualTo("COMPLETED");assertThat(attempts()).isEqualTo(1);
        assertThat(owner.queryForObject("SELECT completed_at FROM payment_refund WHERE id=?",java.sql.Timestamp.class,refundId)).isNotNull();
        assertThat(owner.queryForObject("SELECT count(*) FROM payment_application WHERE establishment_id=?",Integer.class,ownerId)).isEqualTo(1);
        assertThat(owner.queryForObject("SELECT count(*) FROM subscription_cycle WHERE establishment_id=?",Integer.class,ownerId)).isEqualTo(1);
        assertThat(owner.queryForObject("SELECT count(*) FROM payment_refund_log WHERE id=? AND actor_kind='SYSTEM'",Integer.class,refundId)).isGreaterThan(0);
        verify(refunds,times(1)).create(any());
    }
    @Test void pendingIsPolledByIdRatherThanCreatingAgain() {
        doReturn(new Result("re_pending",Status.PENDING,"req_pending")).when(refunds).create(any());
        processor.processDue();assertThat(state()).isEqualTo("PROCESSING");
        assertThat(owner.queryForObject("SELECT completed_at FROM payment_refund WHERE id=?",java.sql.Timestamp.class,refundId)).isNull();
        assertThat(owner.queryForObject("SELECT next_attempt_at>clock_timestamp()+interval '4 minutes' FROM payment_refund WHERE id=?",Boolean.class,refundId)).isTrue();
        due();processor.processDue();assertThat(state()).isEqualTo("COMPLETED");assertThat(attempts()).isEqualTo(2);
        verify(refunds,times(1)).create(any());verify(refunds).retrieve(any(),eq("re_pending"));
    }
    @Test void timeoutPreservesKeyAndRetriesOnlyAfterTheScheduledTime() {
        doThrow(new Failure(FailureKind.TEMPORARY)).doReturn(new Result("re_recovered",Status.SUCCEEDED,null)).when(refunds).create(any());
        processor.processDue();assertThat(state()).isEqualTo("FAILED");processor.processDue();assertThat(attempts()).isEqualTo(1);
        due();processor.processDue();assertThat(state()).isEqualTo("COMPLETED");assertThat(attempts()).isEqualTo(2);
        var requests=org.mockito.ArgumentCaptor.forClass(Request.class);verify(refunds,times(2)).create(requests.capture());
        assertThat(requests.getAllValues().get(0)).isEqualTo(requests.getAllValues().get(1));
    }
    @Test void unknownCreationOlderThan24HoursRequiresReconciliationWithoutAnotherPost() {
        doThrow(new Failure(FailureKind.TEMPORARY)).when(refunds).create(any());processor.processDue();
        owner.update("UPDATE payment_refund_attempt SET started_at=clock_timestamp()-interval '25 hours' WHERE refund_id=?",refundId);
        due();clearInvocations(refunds);processor.processDue();
        assertThat(state()).isEqualTo("FAILED");assertThat(attempts()).isEqualTo(1);
        assertThat(owner.queryForObject("SELECT last_error FROM payment_refund WHERE id=?",String.class,refundId)).isEqualTo("REFUND_RECONCILIATION_REQUIRED");
        assertThat(owner.queryForObject("SELECT next_attempt_at FROM payment_refund WHERE id=?",java.sql.Timestamp.class,refundId)).isNull();
        verifyNoInteractions(refunds);
    }
    @Test void knownIdContinuesToBeConsultedAfter24Hours() {
        doReturn(new Result("re_known",Status.PENDING,null)).when(refunds).create(any());processor.processDue();
        owner.update("UPDATE payment_refund_attempt SET started_at=clock_timestamp()-interval '25 hours' WHERE refund_id=?",refundId);
        due();processor.processDue();assertThat(state()).isEqualTo("COMPLETED");verify(refunds).retrieve(any(),eq("re_known"));
    }
    @Test void definiteFailureNeverStartsAnotherRefundAutomatically() {
        doReturn(new Result("re_failed",Status.FAILED,null)).when(refunds).create(any());processor.processDue();processor.processDue();
        assertThat(state()).isEqualTo("FAILED");assertThat(attempts()).isEqualTo(1);
        assertThat(owner.queryForObject("SELECT next_attempt_at FROM payment_refund WHERE id=?",java.sql.Timestamp.class,refundId)).isNull();
        verify(refunds,times(1)).create(any());
    }
    @Test void permanentHttpFailureIsRecordedWithoutLeakingItsDetails() {
        doThrow(new Failure(FailureKind.REVIEW_REQUIRED)).when(refunds).create(any());processor.processDue();processor.processDue();
        assertThat(attempts()).isEqualTo(1);assertThat(state()).isEqualTo("FAILED");
        assertThat(owner.queryForObject("SELECT error_description FROM payment_refund_attempt WHERE refund_id=?",String.class,refundId)).isEqualTo("REFUND_REVIEW_REQUIRED");
    }
    @Test void interruptedClaimIsReclaimedAndOldWorkerCannotOverwriteTheNewResult() {
        var old=transactions.claim(refundId).getFirst();due();var current=transactions.claim(refundId).getFirst();
        assertThat(attempts()).isEqualTo(2);
        transactions.complete(old,new Result("re_obsolete",Status.SUCCEEDED,null));transactions.fail(old,FailureKind.TEMPORARY);
        assertThat(state()).isEqualTo("PROCESSING");
        transactions.complete(current,new Result("re_current",Status.SUCCEEDED,null));assertThat(state()).isEqualTo("COMPLETED");
        assertThat(owner.queryForObject("SELECT provider_refund_id FROM payment_refund WHERE id=?",String.class,refundId)).isEqualTo("re_current");
    }
    @Test void concurrentWorkersClaimOneRefundOnly() throws Exception {
        var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            Callable<Void> job=()->{start.await();processor.processDue();return null;};
            var a=pool.submit(job);var b=pool.submit(job);start.countDown();a.get(30,TimeUnit.SECONDS);b.get(30,TimeUnit.SECONDS);
        }
        assertThat(state()).isEqualTo("COMPLETED");assertThat(attempts()).isEqualTo(1);verify(refunds,times(1)).create(any());
    }
    @Test void interruptionAfterProviderSuccessRecoversTheSameRefundIdentity() {
        var abandoned=transactions.claim(refundId).getFirst();
        var external=refunds.create(abandoned.request());
        // The process stopped before persisting the provider response.
        assertThat(state()).isEqualTo("PROCESSING");
        assertThat(owner.queryForObject("SELECT provider_refund_id FROM payment_refund WHERE id=?",String.class,refundId)).isNull();
        due();processor.processDue();
        assertThat(state()).isEqualTo("COMPLETED");assertThat(attempts()).isEqualTo(2);
        assertThat(owner.queryForObject("SELECT provider_refund_id FROM payment_refund WHERE id=?",String.class,refundId)).isEqualTo(external.providerId());
        var requests=org.mockito.ArgumentCaptor.forClass(Request.class);verify(refunds,times(2)).create(requests.capture());
        assertThat(requests.getAllValues().get(0)).isEqualTo(requests.getAllValues().get(1));
        transactions.fail(abandoned,FailureKind.TEMPORARY);assertThat(state()).isEqualTo("COMPLETED");
    }
    @Test void incompatibleIdentityCannotReplaceThePersistedRefund() {
        doReturn(new Result("re_original",Status.PENDING,null)).when(refunds).create(any());processor.processDue();
        doReturn(new Result("re_wrong",Status.SUCCEEDED,null)).when(refunds).retrieve(any(),anyString());
        due();processor.processDue();assertThat(state()).isEqualTo("FAILED");
        assertThat(owner.queryForObject("SELECT provider_refund_id FROM payment_refund WHERE id=?",String.class,refundId)).isEqualTo("re_original");
        assertThat(owner.queryForObject("SELECT completed_at FROM payment_refund WHERE id=?",java.sql.Timestamp.class,refundId)).isNull();
    }

}
