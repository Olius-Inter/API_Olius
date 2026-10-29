package br.com.olius.olius_core_api;

import br.com.olius.olius_core_api.dto.CollectionRequestContracts.*;
import br.com.olius.olius_core_api.entity.enums.CollectionRequestStatus;
import br.com.olius.olius_core_api.exception.BusinessRuleException;
import br.com.olius.olius_core_api.exception.ResourceConflictException;
import br.com.olius.olius_core_api.service.CollectionRequestService;
import br.com.olius.olius_core_api.testfixture.OfficialPostgres;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.junit.jupiter.*;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@Testcontainers @AutoConfigureMockMvc
@SpringBootTest(properties={"spring.config.import=","olius.security.ephemeral-keys=true",
    "olius.security.auth-requests-per-minute=10000","olius.admin-invitations.email.enabled=false",
    "olius.stripe.enabled=false","springdoc.api-docs.enabled=true"})
class CollectionRequestsIntegrationTest {
    @Container static final OfficialPostgres POSTGRES=new OfficialPostgres();
    private static final String ROOT="/api/v1/collection-requests";
    private static final String ADMIN="/api/v1/admin/collection-requests";
    private static final String PASSWORD="Password1!";
    private static final String REASON="Acordo externo documentado";
    @DynamicPropertySource static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url",POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username",()->OfficialPostgres.API_USER);
        properties.add("spring.datasource.password",()->OfficialPostgres.API_PASSWORD);
    }
    @Autowired CollectionRequestService service;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired PasswordEncoder passwords;
    private JdbcTemplate owner;
    private Establishment account;
    private UUID accountId,adminId;
    private Account admin;
    private String passwordHash;

    @BeforeEach void fixture() throws Exception {
        owner=new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword()));
        passwordHash=passwords.encode(PASSWORD);
        account=establishment(); admin=administrator();
        accountId=account.id(); adminId=admin.id();
    }

    @Test void createHttpReservesAndReturnsOnlyServerControlledIdentity() throws Exception {
        var result=mvc.perform(createHttp(account.access(),UUID.randomUUID(),"10.25"))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("PENDING"))
            .andExpect(jsonPath("$.establishmentId").value(accountId.toString()))
            .andExpect(jsonPath("$.subscriptionCycleId").value(account.cycle().toString()))
            .andExpect(jsonPath("$.estimatedVolumeLiters").value(10.25))
            .andExpect(jsonPath("$.requestAt").isNotEmpty()).andReturn();
        String id=json.readTree(result.getResponse().getContentAsString()).get("id").asText();
        assertThat(result.getResponse().getHeader("Location")).isEqualTo(ROOT+"/"+id);
        var balance=service.availability(account.cycle(),accountId);
        assertThat(balance.reservedVolumeLiters()).isEqualByComparingTo("10.25");
        assertThat(balance.availableVolumeLiters()).isEqualByComparingTo("39.75");
        assertThat(balance.reservedCollectionSlots()).isEqualTo(1);
        assertThat(balance.availableCollectionSlots()).isEqualTo(1);
        assertNoPhysicalEffects(accountId);
        assertThat(owner.queryForObject("SELECT count(*) FROM collection_request_log WHERE id=? AND operation='INSERT' AND snapshot_kind='AFTER' AND actor_kind='USER' AND performed_by=?",Integer.class,UUID.fromString(id),accountId)).isEqualTo(1);
    }
    @Test void replayReturnsCurrentRequestWithoutNewReservationEvenAfterExpiryOrScheduling() {
        UUID key=UUID.randomUUID(); Create payload=payload("10");
        var first=service.create(accountId,payload,key);
        schedule(first.id(),Instant.now().plusSeconds(36000));
        owner.update("UPDATE subscription_cycle SET ends_at=clock_timestamp()-interval '1 second' WHERE id=?",account.cycle());
        var repeated=service.create(accountId,payload,key);
        assertThat(repeated.id()).isEqualTo(first.id());
        assertThat(repeated.status()).isEqualTo(CollectionRequestStatus.APPROVED);
        assertThat(countRequests()).isEqualTo(1);
        assertThat(service.availability(account.cycle(),accountId).reservedVolumeLiters()).isEqualByComparingTo("10");
        Create changed=payload("11");
        assertThatThrownBy(()->service.create(accountId,changed,key)).isInstanceOf(ResourceConflictException.class);
    }
    @Test void unavailableSubscriptionAndCycleCannotCreateNewRequests() {
        owner.update("UPDATE establishment_subscription SET status='INACTIVE',inactivated_at=clock_timestamp() WHERE id=?",account.subscription());
        Create payload=payload("10"); UUID key=UUID.randomUUID();
        assertThatThrownBy(()->service.create(accountId,payload,key)).isInstanceOf(BusinessRuleException.class);
        owner.update("UPDATE establishment_subscription SET status='ACTIVE',inactivated_at=NULL WHERE id=?",account.subscription());
        owner.update("UPDATE subscription_cycle SET ends_at=clock_timestamp()-interval '1 second' WHERE id=?",account.cycle());
        assertThatThrownBy(()->service.create(accountId,payload,key)).isInstanceOf(BusinessRuleException.class);
        assertThat(countRequests()).isZero();
    }
    @Test void unavailableLitersOrSlotsRollbackCompletely() {
        service.create(accountId,payload("30"),UUID.randomUUID());
        Create tooLarge=payload("21"); UUID key=UUID.randomUUID();
        assertThatThrownBy(()->service.create(accountId,tooLarge,key)).isInstanceOf(BusinessRuleException.class);
        service.create(accountId,payload("1"),UUID.randomUUID());
        Create small=payload("1"); UUID next=UUID.randomUUID();
        assertThatThrownBy(()->service.create(accountId,small,next)).isInstanceOf(BusinessRuleException.class);
        assertThat(countRequests()).isEqualTo(2);
        assertThat(service.availability(account.cycle(),accountId).reservedVolumeLiters()).isEqualByComparingTo("31");
    }
    @Test void concurrentDifferentKeysCannotReserveLastSlotTwice() throws Exception {
        service.create(accountId,payload("1"),UUID.randomUUID());
        assertThat(race(()->createOutcome(UUID.randomUUID(),"1"),()->createOutcome(UUID.randomUUID(),"1")))
            .containsExactlyInAnyOrder("CREATED","CONFLICT");
        assertThat(countRequests()).isEqualTo(2);
        assertThat(service.availability(account.cycle(),accountId).availableCollectionSlots()).isZero();
    }
    @Test void concurrentDifferentKeysCannotOverbookLiters() throws Exception {
        assertThat(race(()->createOutcome(UUID.randomUUID(),"30"),()->createOutcome(UUID.randomUUID(),"30")))
            .containsExactlyInAnyOrder("CREATED","CONFLICT");
        assertThat(countRequests()).isEqualTo(1);
        assertThat(service.availability(account.cycle(),accountId).availableVolumeLiters()).isEqualByComparingTo("20");
    }
    @Test void concurrentIdenticalKeysReturnTheSameIdentity() throws Exception {
        UUID key=UUID.randomUUID();
        var results=race(()->service.create(accountId,payload("10"),key).id().toString(),
                ()->service.create(accountId,payload("10"),key).id().toString());
        assertThat(results.get(0)).isEqualTo(results.get(1));
        assertThat(countRequests()).isEqualTo(1);
    }
    @Test void concurrentSameKeyAndDifferentPayloadsConflict() throws Exception {
        UUID key=UUID.randomUUID();
        assertThat(race(()->createOutcome(key,"10"),()->createOutcome(key,"11")))
            .containsExactlyInAnyOrder("CREATED","CONFLICT");
        assertThat(countRequests()).isEqualTo(1);
    }
    @Test void ownershipProtectsDetailsHistoryAvailabilityAndCancellationIncludingReplay() throws Exception {
        var request=create("10"); var other=establishment();
        service.cancel(request.id(),accountId,false,new Reason(REASON));
        for(String suffix:List.of("","/schedule-history"))
            mvc.perform(get(ROOT+"/"+request.id()+suffix).header("Authorization","Bearer "+other.access())).andExpect(status().isNotFound());
        mvc.perform(post(ROOT+"/"+request.id()+"/cancellation").header("Authorization","Bearer "+other.access())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(new Reason(REASON))))
            .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/billing/cycles/"+account.cycle()+"/availability").header("Authorization","Bearer "+other.access()))
            .andExpect(status().isNotFound());
        mvc.perform(get(ROOT).header("Authorization","Bearer "+other.access())).andExpect(status().isOk())
            .andExpect(jsonPath("$.totalElements").value(0));
    }
    @Test void protectedRoutesRejectAnonymousWrongProfileAndInactiveAccount() throws Exception {
        var request=create("10");
        mvc.perform(get(ROOT)).andExpect(status().isUnauthorized());
        mvc.perform(get(ROOT).header("Authorization","Bearer "+admin.access())).andExpect(status().isForbidden());
        mvc.perform(get(ADMIN).header("Authorization","Bearer "+account.access())).andExpect(status().isForbidden());
        mvc.perform(post(ADMIN+"/"+request.id()+"/scheduling").header("Authorization","Bearer "+account.access())
                .contentType(MediaType.APPLICATION_JSON).content("{}" )).andExpect(status().isForbidden());
        owner.update("UPDATE users SET status='INACTIVE' WHERE id=?",accountId);
        mvc.perform(get(ROOT).header("Authorization","Bearer "+account.access())).andExpect(status().isUnauthorized());
        UUID id=request.id();
        assertThatThrownBy(()->service.detail(id,accountId,false)).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    }
    @Test void queriesFilterPaginateAndNeverExposePersonalOrAuditFields() throws Exception {
        var first=create("10"); create("11");
        service.reject(first.id(),adminId,new Reason(REASON));
        mvc.perform(get(ROOT).param("size","1").header("Authorization","Bearer "+account.access()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(2)).andExpect(jsonPath("$.items.length()").value(1));
        mvc.perform(get(ROOT).param("status","REJECTED").param("cycleId",account.cycle().toString())
                .param("from",Instant.now().minusSeconds(3600).toString()).param("until",Instant.now().plusSeconds(3600).toString())
                .header("Authorization","Bearer "+account.access()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1))
            .andExpect(jsonPath("$.items[0].id").value(first.id().toString()));
        mvc.perform(get(ROOT).param("page","2").param("size","1").header("Authorization","Bearer "+account.access()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.items").isEmpty()).andExpect(jsonPath("$.totalElements").value(2));
        var response=mvc.perform(get(ADMIN).param("establishmentId",accountId.toString()).header("Authorization","Bearer "+admin.access()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(2)).andReturn().getResponse().getContentAsString();
        assertThat(response).doesNotContain("password", "idempotencyKey", "cnpj", "cpf", "qrToken", "audit_reason");
    }
    @Test void invalidInputAndServerControlledFieldsAreRejectedWithoutWrites() throws Exception {
        for(String value:List.of("0","-1","1.001","1000000","1e100"))
            mvc.perform(createHttp(account.access(),UUID.randomUUID(),value)).andExpect(status().isBadRequest());
        for(Map<String,Object> input:List.<Map<String,Object>>of(Map.of("estimatedVolumeLiters",10,"status","APPROVED"),
                Map.of("estimatedVolumeLiters",10,"establishmentId",adminId),Map.of("estimatedVolumeLiters",10,"observation","   ")))
            mvc.perform(post(ROOT).header("Authorization","Bearer "+account.access()).header("Idempotency-Key",UUID.randomUUID())
                    .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(input))).andExpect(status().isBadRequest());
        mvc.perform(post(ROOT).header("Authorization","Bearer "+account.access()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"estimatedVolumeLiters\":10}")).andExpect(status().isBadRequest());
        for(String[] params:List.of(new String[]{"page","-1"},new String[]{"size","101"},new String[]{"status","COMPLETED"},new String[]{"page","abc"}))
            mvc.perform(get(ROOT).param(params[0],params[1]).header("Authorization","Bearer "+account.access())).andExpect(status().isBadRequest());
        mvc.perform(get(ROOT).param("from","2030-01-01T00:00:00Z").param("until","2020-01-01T00:00:00Z")
                .header("Authorization","Bearer "+account.access())).andExpect(status().isBadRequest());
        assertThat(countRequests()).isZero();
    }
    @Test void schedulingApprovesAndReplayingDoesNotDuplicateHistory() throws Exception {
        var request=create("10"); Instant scheduled=Instant.now().plusSeconds(36000).truncatedTo(ChronoUnit.MICROS);
        var input=new Scheduling(scheduled,Instant.now().minusSeconds(1),REASON);
        for(int i=0;i<2;i++)
            mvc.perform(post(ADMIN+"/"+request.id()+"/scheduling").header("Authorization","Bearer "+admin.access())
                    .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(input)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.approval.approvedBy").value(adminId.toString()));
        assertThat(service.history(request.id(),accountId,false,new Pagination(null,null)).totalElements()).isEqualTo(1);
        Instant approved=service.detail(request.id(),accountId,false).approval().approvedAt();
        service.schedule(request.id(),adminId,new Scheduling(scheduled.plusSeconds(3600),Instant.now().minusSeconds(1),"Novo acordo"));
        var history=service.history(request.id(),accountId,false,new Pagination(0,1));
        assertThat(history.totalElements()).isEqualTo(2);
        assertThat(history.items().getFirst().previousScheduledAt()).isEqualTo(scheduled);
        assertThat(service.detail(request.id(),accountId,false).approval().approvedAt()).isEqualTo(approved);
        mvc.perform(get(ROOT+"/"+request.id()+"/schedule-history").param("page","2").param("size","1")
                .header("Authorization","Bearer "+account.access())).andExpect(status().isOk()).andExpect(jsonPath("$.items").isEmpty());
    }
    @Test void invalidSchedulingAndRejectionStatesHaveNoPartialEffects() throws Exception {
        var request=create("10");
        mvc.perform(post(ADMIN+"/"+request.id()+"/scheduling").header("Authorization","Bearer "+admin.access())
                .contentType(MediaType.APPLICATION_JSON).content("{}" )).andExpect(status().isBadRequest());
        var future=new Scheduling(Instant.now().plusSeconds(3600),Instant.now().plusSeconds(3600),REASON);
        UUID requestId=request.id();
        assertThatThrownBy(()->service.schedule(requestId,adminId,future)).isInstanceOf(BusinessRuleException.class);
        var beforeRequest=new Scheduling(request.requestAt().minusSeconds(1),Instant.now().minusSeconds(1),REASON);
        assertThatThrownBy(()->service.schedule(requestId,adminId,beforeRequest)).isInstanceOf(BusinessRuleException.class);
        assertThat(service.detail(request.id(),accountId,false).status()).isEqualTo(CollectionRequestStatus.PENDING);
        assertThat(service.history(request.id(),accountId,false,new Pagination(0,20)).items()).isEmpty();
        schedule(request.id(),Instant.now().plusSeconds(36000));
        var reason=new Reason(REASON);
        assertThatThrownBy(()->service.reject(requestId,adminId,reason)).isInstanceOf(BusinessRuleException.class);
    }
    @Test void rejectionReleasesReservationAndPreservesReasonInAuditOnly() throws Exception {
        var request=create("10");
        mvc.perform(post(ADMIN+"/"+request.id()+"/rejection").header("Authorization","Bearer "+admin.access())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(new Reason(REASON))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REJECTED"));
        mvc.perform(post(ADMIN+"/"+request.id()+"/rejection").header("Authorization","Bearer "+admin.access())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(new Reason(REASON))))
            .andExpect(status().isConflict());
        assertThat(service.availability(account.cycle(),accountId).availableVolumeLiters()).isEqualByComparingTo("50");
        assertThat(owner.queryForObject("SELECT count(*) FROM collection_request_log WHERE id=? AND status='REJECTED' AND snapshot_kind='AFTER' AND performed_by=? AND audit_reason=?",Integer.class,request.id(),adminId,REASON)).isEqualTo(1);
        assertNoPhysicalEffects(accountId);
    }
    @Test void pendingCancellationIsFreeAndAuthorizedReplayKeepsOriginalReason() throws Exception {
        var request=create("10");
        mvc.perform(post(ROOT+"/"+request.id()+"/cancellation").header("Authorization","Bearer "+account.access())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(new Reason(REASON))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.cancellation.policy").value("FREE"));
        mvc.perform(get(ROOT+"/"+request.id()).header("Authorization","Bearer "+account.access()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));
        var cancelled=service.detail(request.id(),accountId,false);
        var repeated=service.cancel(request.id(),accountId,false,new Reason("Reenvio"));
        assertThat(cancelled.cancellation().policy()).isEqualTo("FREE");
        assertThat(repeated.cancellation()).isEqualTo(cancelled.cancellation());
        assertThat(service.availability(account.cycle(),accountId).reservedVolumeLiters()).isZero();
        assertThat(service.availability(account.cycle(),accountId).availableCollectionSlots()).isEqualTo(2);
        assertNoPhysicalEffects(accountId);
    }
    @Test void cancellationBeforeFourHoursIsFreeAndAfterBoundaryForfeitsOnce() {
        var free=create("10"); schedule(free.id(),Instant.now().plusSeconds(5*3600));
        assertThat(service.cancel(free.id(),accountId,false,new Reason(REASON)).cancellation().policy()).isEqualTo("FREE");
        var late=create("12"); schedule(late.id(),Instant.now().plusSeconds(3*3600));
        var cancelled=service.cancel(late.id(),accountId,false,new Reason(REASON));
        assertThat(cancelled.cancellation().policy()).isEqualTo("LATE_FORFEITURE");
        assertThat(cancelled.cancellation().forfeitedVolumeLiters()).isEqualByComparingTo("12");
        service.cancel(late.id(),accountId,false,new Reason(REASON));
        var balance=service.availability(account.cycle(),accountId);
        assertThat(balance.reservedVolumeLiters()).isZero(); assertThat(balance.forfeitedVolumeLiters()).isEqualByComparingTo("12");
        assertThat(balance.availableVolumeLiters()).isEqualByComparingTo("38");
        assertThat(balance.availableCollectionSlots()).isEqualTo(1); assertNoPhysicalEffects(accountId);
    }
    @Test void operationCancellationIsFreeAndCannotAcceptClientChosenPolicy() throws Exception {
        var request=create("10"); schedule(request.id(),Instant.now().plusSeconds(3600));
        mvc.perform(post(ADMIN+"/"+request.id()+"/cancellation").header("Authorization","Bearer "+admin.access())
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Falha operacional\",\"policy\":\"LATE_FORFEITURE\"}"))
            .andExpect(status().isBadRequest());
        mvc.perform(post(ADMIN+"/"+request.id()+"/cancellation").header("Authorization","Bearer "+admin.access())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(new Reason(REASON))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.cancellation.initiative").value("OPERATION"))
            .andExpect(jsonPath("$.cancellation.policy").value("FREE"));
        assertNoPhysicalEffects(accountId);
    }
    @Test void absentOrLateArrivalAfterToleranceAllowsFreeCancellation() {
        var absent=create("10"); schedule(absent.id(),Instant.now().minusSeconds(2*3600));
        assertThat(service.cancel(absent.id(),accountId,false,new Reason(REASON)).cancellation().policy()).isEqualTo("FREE");
        var late=create("10"); schedule(late.id(),Instant.now().minusSeconds(2*3600));
        owner.queryForObject("SELECT record_collection_arrival(?,?)",Timestamp.class,late.id(),driver());
        assertThat(service.cancel(late.id(),accountId,false,new Reason(REASON)).cancellation().policy()).isEqualTo("FREE");
    }
    @Test void arrivalExactlyAtOneHourIsLateForTheCancellationPolicy() {
        var request=create("10"); schedule(request.id(),Instant.now().minusSeconds(2*3600));
        owner.update("UPDATE collection_request SET arrived_at=scheduled_at+interval '1 hour',arrival_recorded_at=clock_timestamp(),arrival_driver_id=? WHERE id=?",driver(),request.id());
        assertThat(service.cancel(request.id(),accountId,false,new Reason(REASON)).cancellation().policy()).isEqualTo("FREE");
        assertNoPhysicalEffects(accountId);
    }
    @Test void storedCancellationAtFourHourBoundaryCannotForfeitResources() {
        var request=create("10"); schedule(request.id(),Instant.now().minusSeconds(5*3600));
        UUID id=request.id();
        // Fixture histórica determinística: valida a fronteira exata da constraint sem
        // esperar horas ou substituir o relógio/rotina oficial do PostgreSQL.
        owner.update("""
                UPDATE collection_request SET status='CANCELLED',cancelled_at=scheduled_at-interval '4 hours',
                    cancellation_recorded_at=clock_timestamp(),cancelled_by=?,cancellation_initiative='ESTABLISHMENT',
                    cancellation_policy='FREE',cancellation_reason='Cancelamento na fronteira'
                WHERE id=?
                """,accountId,id);
        Throwable failure=catchThrowable(()->owner.update("UPDATE collection_request SET cancellation_policy='LATE_FORFEITURE',forfeited_volume_liters=estimated_volume_liters,forfeited_collection_slots=1 WHERE id=?",id));
        assertThat(failure).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        var sql=(org.postgresql.util.PSQLException)failure.getCause();
        assertThat(sql.getSQLState()).isEqualTo("23514");
        assertThat(sql.getServerErrorMessage().getConstraint()).isEqualTo("ck_collection_request_cancellation");
        assertThat(service.detail(id,accountId,false).cancellation().policy()).isEqualTo("FREE");
        assertNoPhysicalEffects(accountId);
    }
    @Test void punctualArrivalKeepsLatePolicyAndAcceptanceBlocksCancellationAndRescheduling() {
        var punctual=create("10"); schedule(punctual.id(),Instant.now().minusSeconds(2*3600));
        owner.update("UPDATE collection_request SET arrived_at=scheduled_at+interval '30 minutes',arrival_recorded_at=clock_timestamp(),arrival_driver_id=? WHERE id=?",driver(),punctual.id());
        assertThat(service.cancel(punctual.id(),accountId,false,new Reason(REASON)).cancellation().policy()).isEqualTo("LATE_FORFEITURE");
        var accepted=create("10"); schedule(accepted.id(),Instant.now().minusSeconds(2*3600));
        owner.queryForObject("SELECT record_collection_arrival(?,?)",Timestamp.class,accepted.id(),driver());
        owner.queryForObject("SELECT accept_collection_service(?,?)",Timestamp.class,accepted.id(),accountId);
        Reason reason=new Reason(REASON); UUID acceptedId=accepted.id();
        assertThatThrownBy(()->service.cancel(acceptedId,accountId,false,reason)).isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(()->service.cancel(acceptedId,adminId,true,reason)).isInstanceOf(BusinessRuleException.class);
        var input=new Scheduling(Instant.now().plusSeconds(3600),Instant.now().minusSeconds(1),REASON);
        assertThatThrownBy(()->service.schedule(acceptedId,adminId,input)).isInstanceOf(BusinessRuleException.class);
    }
    @Test void historicalRequestRemainsLinkedToOriginalCycleAndDoesNotCreditTheNewOne() {
        var request=create("10");
        owner.update("UPDATE subscription_cycle SET ends_at=clock_timestamp()-interval '1 second' WHERE id=?",account.cycle());
        UUID next=addCycle(accountId,account.subscription(),2);
        schedule(request.id(),Instant.now().plusSeconds(6*3600));
        assertThat(service.detail(request.id(),accountId,false).subscriptionCycleId()).isEqualTo(account.cycle());
        var before=service.availability(next,accountId);
        service.cancel(request.id(),accountId,false,new Reason(REASON));
        assertThat(service.availability(next,accountId)).isEqualTo(before);
        assertThat(service.availability(account.cycle(),accountId).reservedVolumeLiters()).isZero();
        assertThat(service.availability(account.cycle(),accountId).cycleCurrent()).isFalse();
    }
    @Test void concurrentSchedulingAndRejectionAllowOnlyOneDecision() throws Exception {
        var request=create("10");
        var input=new Scheduling(Instant.now().plusSeconds(36000),Instant.now().minusSeconds(1),REASON);
        var reason=new Reason(REASON);
        assertThat(race(()->decision(()->service.schedule(request.id(),adminId,input)),
                ()->decision(()->service.reject(request.id(),adminId,reason))))
            .containsExactlyInAnyOrder("OK","CONFLICT");
    }
    @Test void concurrentCancellationCannotCreateDuplicateLoss() throws Exception {
        var request=create("10"); schedule(request.id(),Instant.now().plusSeconds(3600));
        var reason=new Reason(REASON);
        assertThat(race(()->service.cancel(request.id(),accountId,false,reason).id().toString(),
                ()->service.cancel(request.id(),accountId,false,reason).id().toString()))
            .containsOnly(request.id().toString());
        assertThat(service.availability(account.cycle(),accountId).forfeitedCollectionSlots()).isEqualTo(1);
        assertNoPhysicalEffects(accountId);
    }
    @Test void currentAvailabilityDiscoversCycleAndDoesNotInventOneAfterExpiry() throws Exception {
        String route="/api/v1/billing/cycles/current/availability";
        mvc.perform(get(route).header("Authorization","Bearer "+account.access()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.cycleId").value(account.cycle().toString()))
            .andExpect(jsonPath("$.availableVolumeLiters").value(50));
        owner.update("UPDATE subscription_cycle SET ends_at=clock_timestamp()-interval '1 second' WHERE id=?",account.cycle());
        mvc.perform(get(route).header("Authorization","Bearer "+account.access())).andExpect(status().isNotFound());
        mvc.perform(get(route).header("Authorization","Bearer "+admin.access())).andExpect(status().isForbidden());
    }
    @Test void completedCollectionIsVisibleAndCannotBeCancelled() {
        var request=create("10"); schedule(request.id(),Instant.now().plusSeconds(3600));
        UUID driver=driver();
        owner.queryForObject("SELECT record_collection_arrival(?,?)",Timestamp.class,request.id(),driver);
        owner.queryForObject("SELECT accept_collection_service(?,?)",Timestamp.class,request.id(),accountId);
        UUID collection=owner.queryForObject("CALL record_collection(?,?,10,10,'ACCEPTABLE',false,ARRAY[]::text[],NULL,clock_timestamp(),NULL)",UUID.class,request.id(),driver);
        var detail=service.detail(request.id(),accountId,false);
        assertThat(detail.status()).isEqualTo(CollectionRequestStatus.APPROVED);
        assertThat(detail.collectionId()).isEqualTo(collection);
        var balance=service.availability(account.cycle(),accountId);
        assertThat(balance.reservedCollectionSlots()).isZero();
        assertThat(balance.consumedCollectionSlots()).isEqualTo(1);
        assertThat(balance.consumedVolumeLiters()).isEqualByComparingTo("10");
        UUID id=request.id(); var reason=new Reason(REASON);
        assertThatThrownBy(()->service.cancel(id,accountId,false,reason)).isInstanceOf(BusinessRuleException.class);
    }
    @Test void cancellationAndSchedulingRacePreservesTerminalState() throws Exception {
        var request=create("10"); var reason=new Reason(REASON);
        var input=new Scheduling(Instant.now().plusSeconds(36000),Instant.now().minusSeconds(1),REASON);
        var outcomes=race(()->decision(()->service.schedule(request.id(),adminId,input)),
            ()->decision(()->service.cancel(request.id(),accountId,false,reason)));
        assertThat(outcomes).contains("OK");
        assertThat(service.detail(request.id(),accountId,false).status()).isEqualTo(CollectionRequestStatus.CANCELLED);
        assertThat(service.availability(account.cycle(),accountId).availableVolumeLiters()).isEqualByComparingTo("50");
    }
    @Test void administrationCanReadDetailAndHistoryAndOwnerCanReadAvailability() throws Exception {
        var request=create("10"); schedule(request.id(),Instant.now().plusSeconds(36000));
        mvc.perform(get(ADMIN+"/"+request.id()).header("Authorization","Bearer "+admin.access()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(request.id().toString()));
        mvc.perform(get(ADMIN+"/"+request.id()+"/schedule-history").header("Authorization","Bearer "+admin.access()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1));
        mvc.perform(get("/api/v1/billing/cycles/"+account.cycle()+"/availability").header("Authorization","Bearer "+account.access()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.availableVolumeLiters").value(40))
            .andExpect(jsonPath("$.canAcceptNewRequest").value(true));
    }
    @Test void missingResourceAndBlankReasonUseProblemDetails() throws Exception {
        mvc.perform(get(ROOT+"/"+UUID.randomUUID()).header("Authorization","Bearer "+account.access()))
            .andExpect(status().isNotFound()).andExpect(content().contentTypeCompatibleWith("application/problem+json"));
        var request=create("10");
        for(String path:List.of(ROOT+"/"+request.id()+"/cancellation",ADMIN+"/"+request.id()+"/rejection"))
            mvc.perform(post(path).header("Authorization","Bearer "+(path.startsWith(ADMIN) ? admin.access() : account.access()))
                    .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"   \"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }
    @Test void missingExecutePrivilegeRemainsInternalErrorAndDoesNotLeakSql() throws Exception {
        owner.execute("REVOKE EXECUTE ON PROCEDURE public.create_collection_request(uuid,numeric,text,uuid) FROM olius_api");
        try {
            var response=mvc.perform(createHttp(account.access(),UUID.randomUUID(),"10"))
                .andExpect(status().isInternalServerError()).andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andReturn().getResponse().getContentAsString();
            assertThat(response).doesNotContain("create_collection_request","42501","permission denied","INSERT");
        } finally { owner.execute("GRANT EXECUTE ON PROCEDURE public.create_collection_request(uuid,numeric,text,uuid) TO olius_api"); }
        assertThat(countRequests()).isZero();
    }
    @Test void openApiDocumentsProtectedOperationsAndTheirActualContracts() throws Exception {
        var result=mvc.perform(get("/v3/api-docs")).andExpect(status().isOk()).andReturn();
        var paths=json.readTree(result.getResponse().getContentAsString()).get("paths");
        var create=paths.get(ROOT).get("post");
        assertThat(create.get("description").asText()).contains("Idempotency-Key","sem nova reserva");
        assertThat(create.get("security").toString()).contains("bearer");
        assertThat(create.get("responses").has("201")).isTrue();
        String schema=create.get("requestBody").get("content").get("application/json").get("schema").get("$ref").asText();
        assertThat(schema).endsWith("/CollectionRequestCreate");
        assertThat(paths.get(ADMIN+"/{id}/scheduling").get("post").get("description").asText()).contains("agreedAt");
        assertThat(paths.get(ROOT+"/{id}/cancellation").get("post").get("responses").has("422")).isTrue();
        var filters=new ArrayList<String>();
        paths.get(ROOT).get("get").get("parameters").forEach(parameter->filters.add(parameter.get("name").asText()));
        assertThat(filters).containsExactlyInAnyOrder("status","cycleId","from","until","page","size");
        var pagination=new ArrayList<String>();
        paths.get(ROOT+"/{id}/schedule-history").get("get").get("parameters").forEach(parameter->pagination.add(parameter.get("name").asText()));
        assertThat(pagination).containsExactlyInAnyOrder("id","page","size");
    }

    private RequestView create(String liters) { return service.create(accountId,payload(liters),UUID.randomUUID()); }
    private Create payload(String liters) { return new Create(new BigDecimal(liters),null); }
    private MockHttpServletRequestBuilder createHttp(String access,UUID key,String liters) {
        return post(ROOT).header("Authorization","Bearer "+access).header("Idempotency-Key",key)
            .contentType(MediaType.APPLICATION_JSON).content("{\"estimatedVolumeLiters\":"+liters+"}");
    }
    private void schedule(UUID request,Instant at) {
        owner.update("UPDATE collection_request SET request_at=clock_timestamp()-interval '2 days' WHERE id=?",request);
        service.schedule(request,adminId,new Scheduling(at,Instant.now().minusSeconds(1),REASON));
    }
    private String createOutcome(UUID key,String liters) {
        try { service.create(accountId,payload(liters),key); return "CREATED"; }
        catch(BusinessRuleException | ResourceConflictException expected) { return "CONFLICT"; }
    }
    private String decision(Runnable operation) {
        try { operation.run(); return "OK"; }
        catch(BusinessRuleException expected) { return "CONFLICT"; }
    }
    private List<String> race(Callable<String> first,Callable<String> second) throws Exception {
        var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var a=pool.submit(()->{ start.await(); return first.call(); });
            var b=pool.submit(()->{ start.await(); return second.call(); });
            start.countDown(); return List.of(a.get(60,TimeUnit.SECONDS),b.get(60,TimeUnit.SECONDS));
        }
    }
    private int countRequests() { return owner.queryForObject("SELECT count(*) FROM collection_request WHERE establishment_id=?",Integer.class,accountId); }
    private void assertNoPhysicalEffects(UUID id) {
        assertThat(owner.queryForObject("SELECT count(*) FROM collection WHERE establishment_id=?",Integer.class,id)).isZero();
        assertThat(owner.queryForObject("SELECT count(*) FROM point_calculation WHERE user_id=?",Integer.class,id)).isZero();
        assertThat(owner.queryForObject("SELECT count(*) FROM certificate WHERE establishment_id=?",Integer.class,id)).isZero();
    }
    private Account administrator() throws Exception {
        UUID id=UUID.randomUUID(); String email=id+"@test.invalid";
        owner.update("INSERT INTO users(id,name,email,password_hash,user_type) VALUES (?,'Administrador',?,?,'ADMIN')",id,email,passwordHash);
        return new Account(id,login(email));
    }
    private Establishment establishment() throws Exception {
        UUID id=UUID.randomUUID(),address=UUID.randomUUID(),type=UUID.randomUUID(),sub=UUID.randomUUID();
        String email=id+"@test.invalid",qr=UUID.randomUUID().toString();
        owner.update("INSERT INTO users(id,name,email,password_hash,user_type) VALUES (?,'Empresa',?,?,'ESTABLISHMENT')",id,email,passwordHash);
        owner.update("INSERT INTO addresses(id,owner_kind,state,city,neighborhood,street,number,cep) VALUES (?,'ESTABLISHMENT','SP','Cidade','Bairro','Rua','1','00000000')",address);
        owner.update("INSERT INTO establishment_type(id,name) VALUES (?,?)",type,"Tipo-"+type);
        owner.update("INSERT INTO user_qr_code(user_id,qr_token,user_type) VALUES (?,?,'ESTABLISHMENT')",id,qr);
        owner.update("INSERT INTO establishment(id,cnpj,qr_token,type_id,address_id) VALUES (?,?,?,?,?)",id,Long.toString(ThreadLocalRandom.current().nextLong(10000000000000L,99999999999999L)),qr,type,address);
        owner.update("INSERT INTO establishment_subscription(id,establishment_id,status,created_at,activated_at) VALUES (?,?,'ACTIVE',CURRENT_TIMESTAMP-interval '2 days',CURRENT_TIMESTAMP-interval '2 days')",sub,id);
        return new Establishment(id,sub,addCycle(id,sub,1),login(email));
    }
    private UUID addCycle(UUID id,UUID sub,int number) {
        UUID cycle=UUID.randomUUID(),plan=UUID.randomUUID();
        owner.update("INSERT INTO subscription_plan(id,name,monthly_price,volume_limit_liters,collection_limit) VALUES (?,?,19.90,50,2)",plan,"Plano-"+plan);
        var start=ZonedDateTime.now(ZoneId.of("America/Sao_Paulo")).minusDays(number==1 ? 1 : 0).truncatedTo(ChronoUnit.SECONDS);
        owner.update("""
                INSERT INTO subscription_cycle(id,subscription_id,establishment_id,cycle_number,starts_at,ends_at,
                anchor_day,anchor_local_time,anchor_timezone,plan_id,plan_name,monthly_price,volume_limit_liters,collection_limit)
                VALUES (?,?,?,?,?,?,?,?,'America/Sao_Paulo',?,'Plano de teste',19.90,50,2)
                """,cycle,sub,id,number,Timestamp.from(start.toInstant()),Timestamp.from(start.plusMonths(1).toInstant()),
                start.getDayOfMonth(),java.sql.Time.valueOf(start.toLocalTime()),plan);
        return cycle;
    }
    private UUID driver() {
        UUID id=UUID.randomUUID(); String document=Long.toString(ThreadLocalRandom.current().nextLong(10000000000L,99999999999L));
        owner.update("INSERT INTO driver(id,name,cpf,cnh) VALUES (?,'Motorista',?,?)",id,document,document); return id;
    }
    private String login(String email) throws Exception {
        var result=mvc.perform(post("/api/v1/auth/mobile/login").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("email",email,"password",PASSWORD)))).andExpect(status().isOk()).andReturn();
        return json.readTree(result.getResponse().getContentAsString()).get("accessToken").asText();
    }
    private record Account(UUID id,String access) { }
    private record Establishment(UUID id,UUID subscription,UUID cycle,String access) { }
}
