package br.com.olius.olius_core_api;

import br.com.olius.olius_core_api.dto.CollectionContracts.*;
import br.com.olius.olius_core_api.dto.CollectionContracts.Record;
import br.com.olius.olius_core_api.dto.CollectionContracts.Period;
import br.com.olius.olius_core_api.dto.CollectionRequestContracts;
import br.com.olius.olius_core_api.entity.enums.*;
import br.com.olius.olius_core_api.exception.*;
import br.com.olius.olius_core_api.service.*;
import br.com.olius.olius_core_api.testfixture.OfficialPostgres;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
    "olius.payments.stripe.enabled=false","springdoc.api-docs.enabled=true"})
class CollectionOperationsIntegrationTest {
    @Container static final OfficialPostgres POSTGRES=new OfficialPostgres();
    private static final String OPS="/api/v1/operations/b2b";
    private static final String REQUESTS="/api/v1/collection-requests/";
    private static final String ADMIN="/api/v1/admin/collection-requests/";
    private static final String PASSWORD="Password1!";
    @DynamicPropertySource static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url",POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username",()->OfficialPostgres.API_USER);
        properties.add("spring.datasource.password",()->OfficialPostgres.API_PASSWORD);
    }
    @Autowired CollectionOperationsService operations;
    @Autowired CollectionRequestService requests;
    @Autowired CollectionReadService reads;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired PasswordEncoder passwords;
    private JdbcTemplate owner;
    private Shop shop;
    private UUID adminId,driverId;
    private String adminAccess,hash;
    private DriverAccess access;

    @BeforeEach void fixture() throws Exception {
        owner=new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword()));
        hash=passwords.encode(PASSWORD);shop=shop();
        adminId=UUID.randomUUID();String adminEmail=adminId+"@test.invalid";
        owner.update("INSERT INTO users(id,name,email,password_hash,user_type) VALUES (?,'Admin',?,?,'ADMIN')",adminId,adminEmail,hash);
        adminAccess=login(adminEmail);driverId=UUID.randomUUID();String cpf=document(),cnh=document();
        owner.update("INSERT INTO driver(id,name,cpf,cnh) VALUES (?,'Motorista',?,?)",driverId,cpf,cnh);
        access=new DriverAccess(cpf,cnh,shop.qr());
        for(String reason:List.of("VOLUME_OUT_OF_TOLERANCE","OIL_UNACCEPTABLE","COMPROMISING_OCCURRENCE","ESTABLISHMENT_CLOSED","NO_RESPONSIBLE_AVAILABLE"))
            owner.update("INSERT INTO collection_failure_reason(code,name) VALUES (?,?) ON CONFLICT(code) DO NOTHING",reason,reason);
    }
    @Test void contextIsScopedAndDoesNotRegisterArrival() throws Exception {
        UUID id=scheduled();Shop other=shop();
        var context=operations.context(access,0,20);
        assertThat(context.establishmentId()).isEqualTo(shop.id());assertThat(context.driverId()).isEqualTo(driverId);
        assertThat(context.requests()).extracting(OperationalRequest::id).containsExactly(id);
        assertThat(context.failureReasons()).extracting(FailureReason::code).doesNotContain("ESTABLISHMENT_CLOSED","NO_RESPONSIBLE_AVAILABLE");
        assertThat(requests.detail(id,shop.id(),false).arrival()).isNull();
        var response=mvc.perform(body(post(OPS+"/context"),access,null)).andExpect(status().isOk()).andReturn();
        assertThat(response.getResponse().getContentAsString()).doesNotContain(access.cpf(),access.cnh(),shop.qr(),other.id().toString());
    }
    @Test void credentialsQrAndDriverActivityAreRevalidatedEveryTime() throws Exception {
        UUID id=scheduled();
        mvc.perform(body(post(OPS+"/requests/"+id+"/arrival"),new DriverAccess(access.cpf(),document(),shop.qr()),null)).andExpect(status().isForbidden());
        owner.update("UPDATE driver SET status='INACTIVE' WHERE id=?",driverId);
        mvc.perform(body(post(OPS+"/context"),access,null)).andExpect(status().isForbidden());
        owner.update("UPDATE driver SET status='ACTIVE' WHERE id=?",driverId);
        owner.update("UPDATE user_qr_code SET qr_token=? WHERE user_id=?",UUID.randomUUID().toString(),shop.id());
        mvc.perform(body(post(OPS+"/requests/"+id+"/arrival"),access,null)).andExpect(status().isForbidden());
        assertThat(requests.detail(id,shop.id(),false).arrival()).isNull();
    }
    @Test void arrivalIsExplicitIdempotentAndAuditedAsDriverForm() {
        UUID id=scheduled();var first=operations.arrival(id,access);var second=operations.arrival(id,access);
        assertThat(second.arrival()).isEqualTo(first.arrival());assertThat(second.collectionId()).isNull();
        assertThat(owner.queryForObject("SELECT count(*) FROM collection_request_log WHERE id=? AND operation='UPDATE' AND snapshot_kind='AFTER' AND actor_kind='DRIVER_FORM' AND operational_driver_id=?",Integer.class,id,driverId)).isEqualTo(1);
        assertThat(reads.score(shop.id()).points()).isZero();
    }
    @Test void anotherDriverCannotReplaceAnArrival() throws Exception {
        UUID id=scheduled();operations.arrival(id,access);String other=document();
        owner.update("INSERT INTO driver(name,cpf,cnh) VALUES ('Outro',?,?)",other,other);
        mvc.perform(body(post(OPS+"/requests/"+id+"/arrival"),new DriverAccess(other,other,shop.qr()),null)).andExpect(status().isConflict());
        assertThat(requests.detail(id,shop.id(),false).arrival().driverId()).isEqualTo(driverId);
    }
    @Test void acceptanceRequiresOwnerAndArrivalAndCannotBeGivenByAdmin() throws Exception {
        UUID id=scheduled();
        mvc.perform(post(REQUESTS+id+"/service-acceptance").header("Authorization","Bearer "+shop.access())).andExpect(status().isConflict());
        operations.arrival(id,access);Shop other=shop();
        mvc.perform(post(REQUESTS+id+"/service-acceptance").header("Authorization","Bearer "+other.access())).andExpect(status().isNotFound());
        mvc.perform(post(REQUESTS+id+"/service-acceptance").header("Authorization","Bearer "+adminAccess)).andExpect(status().isForbidden());
        var first=operations.accept(id,shop.id());assertThat(operations.accept(id,shop.id()).acceptance()).isEqualTo(first.acceptance());
        UUID establishment=shop.id();var cancellation=new CollectionRequestContracts.Reason("Desistência");
        assertThatThrownBy(()->requests.cancel(id,establishment,false,cancellation)).isInstanceOf(BusinessRuleException.class);
    }
    @Test void normalCollectionUsesOfficialPointsAndServerTimeWithoutFreeDml() throws Exception {
        UUID id=ready();Instant before=Instant.now();
        var response=mvc.perform(body(post(OPS+"/requests/"+id+"/collection"),collectionInput("10"),null))
            .andExpect(status().isOk()).andExpect(jsonPath("$.collection.result").value("SUCCESSFUL"))
            .andExpect(jsonPath("$.calculation.nominalPoints").value(60)).andReturn();
        UUID collection=UUID.fromString(json.readTree(response.getResponse().getContentAsString()).get("collection").get("id").asText());
        var result=reads.detail(collection,shop.id(),false);
        assertThat(result.collection().collectionDate()).isBetween(before.minusSeconds(1),Instant.now());
        assertThat(result.collection().collectionDate()).isBeforeOrEqualTo(result.collection().createdAt());
        assertThat(result.calculation().components()).extracting(PointComponent::component).contains("VOLUME","SUCCESS_BONUS");
        assertThat(reads.score(shop.id()).points()).isEqualTo(60);
        assertThat(owner.queryForObject("SELECT count(*) FROM collection_log WHERE id=? AND operation='INSERT' AND actor_kind='DRIVER_FORM' AND performed_by IS NULL AND operational_driver_id=?",Integer.class,collection,driverId)).isEqualTo(1);
        assertThat(owner.queryForObject("SELECT has_table_privilege('olius_test_api','collection','INSERT')",Boolean.class)).isFalse();
    }
    @ParameterizedTest @ValueSource(strings={"9","11"})
    void toleranceBoundariesAreInclusive(String liters) {
        var result=operations.registerCollection(ready(),collectionInput(liters));assertThat(result.collection().result()).isEqualTo(CollectionResult.SUCCESSFUL);
    }
    @Test void missingAcceptanceDoesNotCreateAnEvent() throws Exception {
        UUID id=scheduled();operations.arrival(id,access);
        mvc.perform(body(post(OPS+"/requests/"+id+"/collection"),collectionInput("10"),null)).andExpect(status().isConflict());assertNoCollection(id);
    }
    @Test void multipleFailuresApplyOnePenaltyAndCountActualVolumeOnce() {
        operations.registerCollection(ready(),collectionInput("10"));UUID id=ready();
        var input=new Record(access,decimal("8"),decimal("10"),OilCondition.UNACCEPTABLE,true,
                List.of("VOLUME_OUT_OF_TOLERANCE","OIL_UNACCEPTABLE","COMPROMISING_OCCURRENCE"),"Óleo e ocorrência verificados");
        var result=operations.registerCollection(id,input);
        assertThat(result.calculation().nominalPoints()).isEqualTo(-50);assertThat(result.calculation().balanceAfter()).isEqualTo(10);
        assertThat(result.calculation().components()).containsExactly(new PointComponent("FAILURE_PENALTY",-50));
        var metrics=reads.metrics(adminId,driverId,new Period(null,null));
        assertThat(metrics.visits()).isEqualTo(2);assertThat(metrics.unsuccessfulVisits()).isEqualTo(1);
        assertThat(metrics.collectedVolumeLiters()).isEqualByComparingTo("18");
        assertThat(requests.availability(shop.cycle(),shop.id()).consumedVolumeLiters()).isEqualByComparingTo("18");
        assertThat(reads.score(shop.id()).successfulCollectionsCount()).isEqualTo(1);
    }
    @Test void zeroPresentedRequiresObservationAndDoesNotAdvanceRecurrence() throws Exception {
        UUID id=ready();var input=new Record(access,decimal("0"),decimal("0"),OilCondition.NOT_ASSESSED,false,List.of("VOLUME_OUT_OF_TOLERANCE"),null);
        mvc.perform(body(post(OPS+"/requests/"+id+"/collection"),input,null)).andExpect(status().isUnprocessableContent());assertNoCollection(id);
        var result=operations.registerCollection(id,new Record(access,decimal("0"),decimal("0"),OilCondition.NOT_ASSESSED,false,List.of("VOLUME_OUT_OF_TOLERANCE"),"Sem óleo apresentado"));
        assertThat(result.calculation().nominalPoints()).isEqualTo(-50);assertThat(result.calculation().balanceAfter()).isZero();
        assertThat(reads.score(shop.id()).successfulCollectionsCount()).isZero();
    }
    @Test void incompatibleReasonsAndFactsAreRejectedAtomically() throws Exception {
        UUID id=ready();
        for(var input:List.of(new Record(access,decimal("10"),decimal("10"),OilCondition.ACCEPTABLE,false,List.of("OIL_UNACCEPTABLE"),null),
                new Record(access,decimal("11"),decimal("10"),OilCondition.ACCEPTABLE,false,List.of(),null),
                new Record(access,decimal("1"),decimal("0"),OilCondition.NOT_ASSESSED,false,List.of(),"Inconsistente")))
            mvc.perform(body(post(OPS+"/requests/"+id+"/collection"),input,null)).andExpect(status().isUnprocessableContent());
        assertNoCollection(id);
    }
    @Test void replayPreservesOriginalDateAndRejectsDivergentPayload() {
        UUID id=ready();var first=operations.registerCollection(id,collectionInput("10"));var second=operations.registerCollection(id,collectionInput("10"));
        assertThat(second).isEqualTo(first);assertThat(countCollections()).isEqualTo(1);
        var changed=collectionInput("11");
        assertThatThrownBy(()->operations.registerCollection(id,changed)).isInstanceOf(ResourceConflictException.class);
        assertThat(reads.score(shop.id()).points()).isEqualTo(60);
    }
    @Test void replayAfterCorrectionUsesOriginalPayloadAndCurrentCalculation() {
        UUID id=ready();var original=operations.registerCollection(id,collectionInput("10"));
        owner.update("CALL public.correct_collection(?,1,?,'RECORDED',11,11,'ACCEPTABLE',false,ARRAY[]::text[],'Corrigido','Verificação')",original.collection().id(),adminId);
        var replay=operations.registerCollection(id,collectionInput("10"));assertThat(replay.collection().revision()).isEqualTo(2);
        assertThat(replay.calculation().nominalPoints()).isEqualTo(61);assertThat(replay.collection().collectionDate()).isEqualTo(original.collection().collectionDate());
        var changed=collectionInput("11");
        assertThatThrownBy(()->operations.registerCollection(id,changed)).isInstanceOf(ResourceConflictException.class);
    }
    @Test void replayDoesNotRevalidateAnInactiveHistoricalReason() {
        UUID id=ready();var input=new Record(access,decimal("10"),decimal("10"),OilCondition.UNACCEPTABLE,false,List.of("OIL_UNACCEPTABLE"),"Qualidade inadequada");
        var first=operations.registerCollection(id,input);
        owner.update("UPDATE collection_failure_reason SET status='INACTIVE' WHERE code='OIL_UNACCEPTABLE'");
        try { assertThat(operations.registerCollection(id,input)).isEqualTo(first); }
        finally { owner.update("UPDATE collection_failure_reason SET status='ACTIVE' WHERE code='OIL_UNACCEPTABLE'"); }
    }
    @ParameterizedTest @ValueSource(strings={"ESTABLISHMENT_CLOSED","NO_RESPONSIBLE_AVAILABLE"})
    void administrativeVisitHasNoFakeAcceptanceAndOneSlotPenalty(String reason) throws Exception {
        UUID id=scheduled();var arrival=operations.arrival(id,access).arrival();
        var input=new FailedVisit(AdministrativeFailureReason.valueOf(reason),"Ocorrência confirmada pelo administrador");
        mvc.perform(body(post(ADMIN+id+"/failed-visit"),input,shop.access())).andExpect(status().isForbidden());
        var first=operations.failedVisit(id,adminId,input);assertThat(operations.failedVisit(id,adminId,input)).isEqualTo(first);
        assertThat(first.collection().collectionDate()).isEqualTo(arrival.arrivedAt());
        assertThat(first.collection().collectedVolumeLiters()).isZero();assertThat(first.collection().result()).isEqualTo(CollectionResult.UNSUCCESSFUL);
        assertThat(first.calculation().nominalPoints()).isEqualTo(-50);assertThat(first.calculation().balanceAfter()).isZero();
        assertThat(requests.detail(id,shop.id(),false).acceptance()).isNull();
        var availability=requests.availability(shop.cycle(),shop.id());assertThat(availability.consumedCollectionSlots()).isEqualTo(1);
        assertThat(availability.consumedVolumeLiters()).isZero();assertThat(availability.reservedCollectionSlots()).isZero();
        assertThat(owner.queryForObject("SELECT count(*) FROM collection_log WHERE id=? AND operation='INSERT' AND actor_kind='USER' AND performed_by=? AND operational_driver_id IS NULL",Integer.class,first.collection().id(),adminId)).isEqualTo(1);
    }
    @Test void administrativeVisitRequiresArrivalAndCannotReplaceAnAcceptedService() throws Exception {
        UUID id=scheduled();var payload=new FailedVisit(AdministrativeFailureReason.ESTABLISHMENT_CLOSED,"Confirmado");
        mvc.perform(body(post(ADMIN+id+"/failed-visit"),payload,adminAccess)).andExpect(status().isConflict());
        operations.arrival(id,access);operations.accept(id,shop.id());
        mvc.perform(body(post(ADMIN+id+"/failed-visit"),payload,adminAccess)).andExpect(status().isConflict());assertNoCollection(id);
    }
    @Test void expiredSubscriptionDoesNotEraseVisitAndActualExcessConsumesRealLiters() {
        UUID id=ready();owner.update("UPDATE subscription_cycle SET volume_limit_liters=10,collection_limit=1 WHERE id=?",shop.cycle());
        owner.update("UPDATE establishment_subscription SET status='INACTIVE',inactivated_at=clock_timestamp() WHERE id=?",shop.subscription());
        var input=new Record(access,decimal("12"),decimal("12"),OilCondition.ACCEPTABLE,false,List.of("VOLUME_OUT_OF_TOLERANCE"),null);
        operations.registerCollection(id,input);var availability=requests.availability(shop.cycle(),shop.id());
        assertThat(availability.consumedVolumeLiters()).isEqualByComparingTo("12");assertThat(availability.canAcceptNewRequest()).isFalse();
    }
    @Test void recurrenceAndAnnualSourceStayCorrectAtTenthTwentiethHundredthAndBeyond() {
        long total=0;
        for(int n=1;n<=110;n++) {
            var result=operations.registerCollection(ready(),collectionInput("10"));long bonus=n%10==0 ? Math.min(n,100) : 0;total+=60+bonus;
            if(n%10==0) assertThat(result.calculation().components()).contains(new PointComponent("RECURRENCE_BONUS",bonus));
        }
        assertThat(reads.score(shop.id()).points()).isEqualTo(total);
        assertThat(owner.queryForObject("SELECT annual_points FROM public.vw_annual_scores WHERE participant_id=? AND profile='B2B'",Long.class,shop.id())).isEqualTo(total);
        assertThat(owner.queryForObject("SELECT has_table_privilege('olius_test_api','vw_annual_scores','SELECT')",Boolean.class)).isFalse();
    }
    @Test void concurrentEquivalentSubmissionsCreateOneEventAndReuseTheDate() throws Exception {
        UUID id=ready();var results=parallel(()->operations.registerCollection(id,collectionInput("10")),()->operations.registerCollection(id,collectionInput("10")));
        assertThat(results.get(0)).isEqualTo(results.get(1));assertThat(countCollections()).isEqualTo(1);assertThat(reads.score(shop.id()).points()).isEqualTo(60);
    }
    @Test void acceptanceAndCancellationRaceCannotLeaveBothEffects() throws Exception {
        UUID id=scheduled();operations.arrival(id,access);
        parallel(()->attempt(()->operations.accept(id,shop.id())),()->attempt(()->requests.cancel(id,shop.id(),false,new CollectionRequestContracts.Reason("Desistência"))));
        var result=requests.detail(id,shop.id(),false);
        assertThat((result.status()==CollectionRequestStatus.CANCELLED && result.acceptance()==null)
                || (result.status()==CollectionRequestStatus.APPROVED && result.acceptance()!=null)).isTrue();
    }
    @Test void projectionFailureRollsBackCollectionReasonsAuditAndPoints() throws Exception {
        UUID id=ready();
        owner.execute("CREATE FUNCTION test_support.fail_collection_points() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'fixture'; END $$");
        owner.execute("CREATE TRIGGER fail_collection_points BEFORE INSERT ON point_calculation FOR EACH ROW WHEN (NEW.user_id='"+shop.id()+"'::uuid) EXECUTE FUNCTION test_support.fail_collection_points()");
        try { mvc.perform(body(post(OPS+"/requests/"+id+"/collection"),collectionInput("10"),null)).andExpect(status().isInternalServerError()); }
        finally { owner.execute("DROP TRIGGER fail_collection_points ON point_calculation");owner.execute("DROP FUNCTION test_support.fail_collection_points()"); }
        assertNoCollection(id);assertThat(reads.score(shop.id()).points()).isZero();
        assertThat(owner.queryForObject("SELECT count(*) FROM collection_log WHERE collection_request_id=?",Integer.class,id)).isZero();
    }
    @Test void readsAreOwnerScopedAndMetricsUsePhysicalPeriodWithoutAnnulledVisits() throws Exception {
        var first=operations.registerCollection(ready(),collectionInput("10"));var second=operations.registerCollection(ready(),collectionInput("10"));
        owner.update("CALL public.correct_collection(?,1,?,'ANNULLED',10,10,'ACCEPTABLE',false,ARRAY[]::text[],'Anulado','Duplicidade documental')",second.collection().id(),adminId);
        var metrics=reads.metrics(adminId,driverId,new Period(first.collection().collectionDate(),first.collection().collectionDate().plusNanos(1000000)));
        assertThat(metrics.visits()).isEqualTo(1);assertThat(metrics.establishmentsVisited()).isEqualTo(1);
        assertThat(metrics.collectedVolumeLiters()).isEqualByComparingTo("10");
        Shop other=shop();mvc.perform(get("/api/v1/collections/"+first.collection().id()).header("Authorization","Bearer "+other.access())).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/admin/drivers/"+driverId+"/metrics").header("Authorization","Bearer "+shop.access())).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/collections").header("Authorization","Bearer "+shop.access())).andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(2));
        assertThat(reads.detail(second.collection().id(),shop.id(),false).collection().recordStatus()).isEqualTo(RecordStatus.ANNULLED);
    }
    @Test void inputCannotControlTimePointsResultOrIdentityAndMissingExecuteStaysInternal() throws Exception {
        UUID id=ready();var map=json.convertValue(collectionInput("10"),new tools.jackson.core.type.TypeReference<Map<String,Object>>() { });map.put("collectionDate","2020-01-01T00:00:00Z");
        mvc.perform(body(post(OPS+"/requests/"+id+"/collection"),map,null)).andExpect(status().isBadRequest());
        owner.execute("REVOKE EXECUTE ON PROCEDURE public.record_collection(uuid,uuid,numeric,numeric,public.oil_condition_t,boolean,text[],text,timestamptz) FROM olius_api");
        try { mvc.perform(body(post(OPS+"/requests/"+id+"/collection"),collectionInput("10"),null)).andExpect(status().isInternalServerError()); }
        finally { owner.execute("GRANT EXECUTE ON PROCEDURE public.record_collection(uuid,uuid,numeric,numeric,public.oil_condition_t,boolean,text[],text,timestamptz) TO olius_api"); }
        assertNoCollection(id);
    }
    @Test void openApiSeparatesOperationalIdentificationFromBearerEndpoints() throws Exception {
        var result=mvc.perform(get("/v3/api-docs")).andExpect(status().isOk()).andReturn();var paths=json.readTree(result.getResponse().getContentAsString()).get("paths");
        var security=paths.get(OPS+"/requests/{id}/collection").get("post").get("security");
        assertThat(security==null || security.isEmpty()).isTrue();
        assertThat(paths.get(ADMIN+"{id}/failed-visit").get("post").get("security").toString()).contains("bearer");
        assertThat(paths.get("/api/v1/admin/drivers/{id}/metrics").get("get").get("description").asText()).contains("Cada visita conta uma vez");
    }
    @Test void queriesAndMetricsExposeOnlyCurrentCalculationsAndValidatedPeriods() throws Exception {
        var result=operations.registerCollection(ready(),collectionInput("10"));UUID id=result.collection().id();
        for(String route:List.of("/api/v1/admin/collections","/api/v1/admin/drivers/"+driverId+"/collections"))
            mvc.perform(get(route).param("result","SUCCESSFUL").param("recordStatus","RECORDED")
                    .header("Authorization","Bearer "+adminAccess)).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.id == '"+id+"')]").isNotEmpty());
        mvc.perform(get("/api/v1/admin/collections/"+id).header("Authorization","Bearer "+adminAccess))
            .andExpect(status().isOk()).andExpect(jsonPath("$.calculation.nominalPoints").value(60));
        mvc.perform(get("/api/v1/admin/drivers/"+driverId+"/metrics").header("Authorization","Bearer "+adminAccess))
            .andExpect(status().isOk()).andExpect(jsonPath("$.visits").value(1));
        mvc.perform(get("/api/v1/establishments/me/score").header("Authorization","Bearer "+shop.access()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.points").value(60));
        mvc.perform(get("/api/v1/admin/drivers/"+UUID.randomUUID()+"/metrics").header("Authorization","Bearer "+adminAccess)).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/admin/drivers/"+driverId+"/metrics").param("from","2026-12-01T00:00:00Z")
                .param("until","2026-01-01T00:00:00Z").header("Authorization","Bearer "+adminAccess)).andExpect(status().isBadRequest());
        var empty=reads.metrics(adminId,driverId,new Period(null,result.collection().collectionDate()));
        assertThat(empty.visits()).isZero();assertThat(empty.lastCollectionDate()).isNull();
        UUID missing=UUID.randomUUID();
        assertThatThrownBy(()->reads.detail(missing,adminId,true)).isInstanceOf(ResourceNotFoundException.class);
    }
    @Test void actualUnsuccessfulVolumeParticipatesInOfficialCertificateReconciliation() {
        UUID level=UUID.randomUUID();owner.update("INSERT INTO certificate_level(id,name,required_liters) VALUES (?,?,18)",level,"Teste-"+level);
        operations.registerCollection(ready(),collectionInput("10"));
        operations.registerCollection(ready(),new Record(access,decimal("8"),decimal("10"),OilCondition.ACCEPTABLE,false,List.of("VOLUME_OUT_OF_TOLERANCE"),null));
        assertThat(owner.queryForObject("SELECT count(*) FROM certificate WHERE establishment_id=? AND certificate_level_id=?",Integer.class,shop.id(),level)).isEqualTo(1);
    }
    @Test void administrativeReplaysCannotChangeOriginalActorOrReason() {
        UUID id=scheduled();operations.arrival(id,access);
        var input=new FailedVisit(AdministrativeFailureReason.ESTABLISHMENT_CLOSED,"Confirmado");operations.failedVisit(id,adminId,input);
        var differentReason=new FailedVisit(AdministrativeFailureReason.NO_RESPONSIBLE_AVAILABLE,"Confirmado");
        assertThatThrownBy(()->operations.failedVisit(id,adminId,differentReason)).isInstanceOf(ResourceConflictException.class);
        UUID other=UUID.randomUUID();owner.update("INSERT INTO users(id,name,email,password_hash,user_type) VALUES (?,'Outro',?,?,'ADMIN')",other,other+"@test.invalid",hash);
        assertThatThrownBy(()->operations.failedVisit(id,other,input)).isInstanceOf(ResourceConflictException.class);
    }
    @Test void oversizedOperationalInputsAreRejectedBeforeTheyCreateAnyEffects() throws Exception {
        UUID id=scheduled();String oversized="x".repeat(1001);
        var observation=new Record(access,decimal("10"),decimal("10"),OilCondition.ACCEPTABLE,false,List.of(),oversized);
        var reasons=new Record(access,decimal("0"),decimal("0"),OilCondition.NOT_ASSESSED,false,
                Collections.nCopies(101,"VOLUME_OUT_OF_TOLERANCE"),"Sem óleo");
        for(var payload:List.of(observation,reasons))
            mvc.perform(body(post(OPS+"/requests/"+id+"/collection"),payload,null))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        mvc.perform(body(post(ADMIN+id+"/failed-visit"),new FailedVisit(AdministrativeFailureReason.ESTABLISHMENT_CLOSED,oversized),adminAccess))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        assertNoCollection(id);assertThat(requests.detail(id,shop.id(),false).arrival()).isNull();
    }
    @Test void technicalInputBoundariesStillAllowNormalAndAdministrativeRecords() throws Exception {
        String text="x".repeat(1000);UUID normal=ready();
        var payload=new Record(access,decimal("0"),decimal("0"),OilCondition.NOT_ASSESSED,false,
                Collections.nCopies(100,"VOLUME_OUT_OF_TOLERANCE"),text);
        mvc.perform(body(post(OPS+"/requests/"+normal+"/collection"),payload,null))
            .andExpect(status().isOk()).andExpect(jsonPath("$.collection.failureReasonCodes.length()").value(1));
        UUID administrative=scheduled();operations.arrival(administrative,access);
        mvc.perform(body(post(ADMIN+administrative+"/failed-visit"),new FailedVisit(AdministrativeFailureReason.ESTABLISHMENT_CLOSED,text),adminAccess))
            .andExpect(status().isOk()).andExpect(jsonPath("$.calculation.nominalPoints").value(-50));
    }
    @Test void documentRegexStaysAsciiAndQrRemainsAParameterizedValue() throws Exception {
        var nonAscii=new DriverAccess("١".repeat(11),access.cnh(),shop.qr());
        mvc.perform(body(post(OPS+"/context"),nonAscii,null)).andExpect(status().isBadRequest());
        var attemptedSql=new DriverAccess(access.cpf(),access.cnh(),"' OR '1'='1");
        mvc.perform(body(post(OPS+"/context"),attemptedSql,null)).andExpect(status().isForbidden());
        assertThat(operations.context(access,0,20).establishmentId()).isEqualTo(shop.id());
    }
    private UUID scheduled() {
        var created=requests.create(shop.id(),new CollectionRequestContracts.Create(decimal("10"),null),UUID.randomUUID());
        requests.schedule(created.id(),adminId,new CollectionRequestContracts.Scheduling(Instant.now().plusSeconds(86400),Instant.now().minusSeconds(1),"Acordado externamente"));return created.id();
    }
    private UUID ready() { UUID id=scheduled();operations.arrival(id,access);operations.accept(id,shop.id());return id; }
    private Record collectionInput(String liters) { return new Record(access,decimal(liters),decimal(liters),OilCondition.ACCEPTABLE,false,List.of(),null); }
    private BigDecimal decimal(String value) { return new BigDecimal(value); }
    private void assertNoCollection(UUID id) { assertThat(owner.queryForObject("SELECT count(*) FROM collection WHERE collection_request_id=?",Integer.class,id)).isZero(); }
    private int countCollections() { return owner.queryForObject("SELECT count(*) FROM collection WHERE establishment_id=?",Integer.class,shop.id()); }
    private boolean attempt(Callable<?> action) { try { action.call();return true; } catch(Exception expected) { return false; } }
    private <T> List<T> parallel(Callable<T> a,Callable<T> b) throws Exception {
        var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var first=pool.submit(()->{start.await();return a.call();});var second=pool.submit(()->{start.await();return b.call();});
            start.countDown();return List.of(first.get(90,TimeUnit.SECONDS),second.get(90,TimeUnit.SECONDS));
        }
    }
    private MockHttpServletRequestBuilder body(MockHttpServletRequestBuilder request,Object payload,String token) throws Exception {
        if(token!=null) request.header("Authorization","Bearer "+token);
        return request.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(payload));
    }
    private String login(String email) throws Exception {
        var result=mvc.perform(post("/api/v1/auth/mobile/login").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("email",email,"password",PASSWORD)))).andExpect(status().isOk()).andReturn();
        return json.readTree(result.getResponse().getContentAsString()).get("accessToken").asText();
    }
    private Shop shop() throws Exception {
        UUID id=UUID.randomUUID(),address=UUID.randomUUID(),type=UUID.randomUUID(),sub=UUID.randomUUID(),cycle=UUID.randomUUID(),plan=UUID.randomUUID();
        String email=id+"@test.invalid",qr=UUID.randomUUID().toString();
        owner.update("INSERT INTO users(id,name,email,password_hash,user_type) VALUES (?,'Empresa',?,?,'ESTABLISHMENT')",id,email,hash);
        owner.update("INSERT INTO addresses(id,owner_kind,state,city,neighborhood,street,number,cep) VALUES (?,'ESTABLISHMENT','SP','Cidade','Bairro','Rua','1','00000000')",address);
        owner.update("INSERT INTO establishment_type(id,name) VALUES (?,?)",type,"Tipo-"+type);
        owner.update("INSERT INTO user_qr_code(user_id,qr_token,user_type) VALUES (?,?,'ESTABLISHMENT')",id,qr);
        owner.update("INSERT INTO establishment(id,cnpj,qr_token,type_id,address_id) VALUES (?,?,?,?,?)",id,Long.toString(ThreadLocalRandom.current().nextLong(10000000000000L,99999999999999L)),qr,type,address);
        owner.update("INSERT INTO establishment_subscription(id,establishment_id,status,created_at,activated_at) VALUES (?,?,'ACTIVE',CURRENT_TIMESTAMP-interval '2 days',CURRENT_TIMESTAMP-interval '2 days')",sub,id);
        owner.update("INSERT INTO subscription_plan(id,name,monthly_price,volume_limit_liters,collection_limit) VALUES (?,?,19.90,10000,250)",plan,"Plano-"+plan);
        var start=ZonedDateTime.now(ZoneId.of("America/Sao_Paulo")).minusDays(1).truncatedTo(ChronoUnit.SECONDS);
        owner.update("""
                INSERT INTO subscription_cycle(id,subscription_id,establishment_id,cycle_number,starts_at,ends_at,
                anchor_day,anchor_local_time,anchor_timezone,plan_id,plan_name,monthly_price,volume_limit_liters,collection_limit)
                VALUES (?,?,?,1,?,?,?,?,'America/Sao_Paulo',?,'Plano de teste',19.90,10000,250)
                """,cycle,sub,id,Timestamp.from(start.toInstant()),Timestamp.from(start.plusMonths(1).toInstant()),
                start.getDayOfMonth(),java.sql.Time.valueOf(start.toLocalTime()),plan);
        return new Shop(id,sub,cycle,qr,login(email));
    }
    private String document() { return Long.toString(ThreadLocalRandom.current().nextLong(10000000000L,99999999999L)); }
    private record Shop(UUID id,UUID subscription,UUID cycle,String qr,String access) { }
}
