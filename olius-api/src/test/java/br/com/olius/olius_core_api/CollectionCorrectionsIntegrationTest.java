package br.com.olius.olius_core_api;

import br.com.olius.olius_core_api.dto.CollectionContracts.*;
import br.com.olius.olius_core_api.dto.CollectionCorrectionContracts.*;
import br.com.olius.olius_core_api.dto.CollectionContracts.Record;
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
class CollectionCorrectionsIntegrationTest {
    @Container static final OfficialPostgres POSTGRES=new OfficialPostgres();
    private static final String PASSWORD="Password1!";
    @DynamicPropertySource static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url",POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username",()->OfficialPostgres.API_USER);
        properties.add("spring.datasource.password",()->OfficialPostgres.API_PASSWORD);
    }
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
    @Autowired JdbcTemplate apiJdbc;
    @Autowired CollectionCorrectionService corrections;
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
    private static final String COLLECTIONS="/api/v1/admin/collections/";

    @Test void correctionsRequireActiveAdminAndRespectStructuralValidation() throws Exception {
        var original=operations.registerCollection(ready(),collectionInput("10"));UUID id=original.collection().id();
        String route=COLLECTIONS+id+"/corrections";var input=correction(1,"11");
        mvc.perform(body(post(route),input,null)).andExpect(status().isUnauthorized());
        mvc.perform(body(post(route),input,shop.access())).andExpect(status().isForbidden());
        mvc.perform(body(post(route),new Annulment(0," "),adminAccess)).andExpect(status().isBadRequest());
        mvc.perform(body(post(route),correction(1,"-1"),adminAccess)).andExpect(status().isBadRequest());
        var longReason=new Correction(1,"x".repeat(1001),decimal("10"),decimal("10"),OilCondition.ACCEPTABLE,false,List.of(),null);
        mvc.perform(body(post(route),longReason,adminAccess)).andExpect(status().isBadRequest());
        UUID otherAdmin=UUID.randomUUID();
        owner.update("INSERT INTO users(id,name,email,password_hash,user_type) VALUES (?,'Outro admin',?,?,'ADMIN')",
                otherAdmin,otherAdmin+"@test.invalid",hash);
        owner.update("UPDATE users SET status='INACTIVE' WHERE id=?",adminId);
        mvc.perform(body(post(route),input,adminAccess)).andExpect(status().isUnauthorized());
        assertThat(reads.detail(id,shop.id(),false).collection().revision()).isEqualTo(1);
    }

    @Test void correctionIsAuditedPreservesIdentityAndRejectsStaleReplay() throws Exception {
        var original=operations.registerCollection(ready(),collectionInput("10"));UUID id=original.collection().id();
        mvc.perform(body(post(COLLECTIONS+id+"/corrections"),correction(1,"11"),adminAccess))
            .andExpect(status().isOk()).andExpect(jsonPath("$.collection.revision").value(2))
            .andExpect(jsonPath("$.calculation.nominalPoints").value(61));
        var updated=reads.detail(id,shop.id(),false).collection();
        assertThat(updated.requestId()).isEqualTo(original.collection().requestId());
        assertThat(updated.driverId()).isEqualTo(driverId);
        assertThat(updated.collectionDate()).isEqualTo(original.collection().collectionDate());
        assertThat(updated.processingOrder()).isEqualTo(original.collection().processingOrder());
        mvc.perform(body(post(COLLECTIONS+id+"/corrections"),correction(1,"11"),adminAccess))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CONCURRENT_MODIFICATION"));
        assertThat(owner.queryForObject("SELECT count(*) FROM collection_log WHERE id=? AND snapshot_kind='AFTER' AND revision=2 AND actor_kind='USER' AND performed_by=? AND operational_driver_id IS NULL AND audit_reason='Conferência administrativa'",Integer.class,id,adminId)).isEqualTo(1);
        var history=corrections.history(id,adminId,new HistoryQuery(0,20));
        assertThat(history.items()).extracting(CalculationRevision::revision).containsExactly(2,1);
        assertThat(history.items().getFirst().recalculatedBy()).isEqualTo(adminId);
        assertThat(history.items().getLast().components()).contains(new PointComponent("VOLUME",10));
    }

    @Test void annulmentAndRestorationRebuildFloorZeroRatherThanAddingNominalPenalty() {
        // 70 - 50 = 20; depois -50 -> 0 e +60 -> 60.
        operations.registerCollection(readyFor("20"),collectionInput("20"));
        operations.registerCollection(ready(),failure());assertThat(reads.score(shop.id()).points()).isEqualTo(20);
        var penalty=operations.registerCollection(ready(),failure());
        var gain=operations.registerCollection(ready(),collectionInput("10"));
        assertThat(reads.score(shop.id()).points()).isEqualTo(60);
        var annulled=corrections.annul(penalty.collection().id(),adminId,new Annulment(1,"Penalidade indevida"));
        assertThat(annulled.collection().recordStatus()).isEqualTo(RecordStatus.ANNULLED);
        assertThat(reads.score(shop.id()).points()).isEqualTo(80);
        assertThat(requests.availability(shop.cycle(),shop.id()).consumedCollectionSlots()).isEqualTo(3);
        var later=corrections.history(gain.collection().id(),adminId,new HistoryQuery(0,20));
        assertThat(later.items()).extracting(CalculationRevision::balanceAfter).containsExactly(80L,60L);
        assertThat(reads.detail(gain.collection().id(),shop.id(),false).collection().revision()).isEqualTo(1);
        var restored=corrections.restore(penalty.collection().id(),adminId,failureCorrection(2));
        assertThat(restored.collection().revision()).isEqualTo(3);
        assertThat(reads.score(shop.id()).points()).isEqualTo(60);
        assertThat(requests.availability(shop.cycle(),shop.id()).consumedCollectionSlots()).isEqualTo(4);
        assertThat(owner.queryForObject("SELECT count(*) FROM collection_log WHERE id=? AND snapshot_kind='AFTER' AND record_status='ANNULLED'",Integer.class,penalty.collection().id())).isEqualTo(1);
    }

    @Test void correctionAndRestorationCannotChangeAnUnacceptedVisitIntoAcceptedService() throws Exception {
        UUID request=scheduled();operations.arrival(request,access);
        var visit=operations.failedVisit(request,adminId,new FailedVisit(AdministrativeFailureReason.ESTABLISHMENT_CLOSED,"Fechado"));
        UUID id=visit.collection().id();
        mvc.perform(body(post(COLLECTIONS+id+"/corrections"),correction(1,"10"),adminAccess)).andExpect(status().isUnprocessableContent());
        corrections.annul(id,adminId,new Annulment(1,"Revisão da visita"));
        mvc.perform(body(post(COLLECTIONS+id+"/restorations"),correction(2,"10"),adminAccess)).andExpect(status().isUnprocessableContent());
        var valid=new Correction(2,"Confirmada ausência de atendimento",decimal("0"),decimal("0"),OilCondition.NOT_ASSESSED,false,List.of("ESTABLISHMENT_CLOSED"),"Fechado");
        mvc.perform(body(post(COLLECTIONS+id+"/restorations"),valid,adminAccess)).andExpect(status().isOk());
        assertThat(requests.detail(request,shop.id(),false).acceptance()).isNull();
    }

    @Test void invalidFactsAndInactiveReasonsRollbackTheWholeCorrection() throws Exception {
        var original=operations.registerCollection(ready(),collectionInput("10"));UUID id=original.collection().id();
        var incompatible=new Correction(1,"Revisão",decimal("10"),decimal("10"),OilCondition.ACCEPTABLE,false,List.of("OIL_UNACCEPTABLE"),null);
        mvc.perform(body(post(COLLECTIONS+id+"/corrections"),incompatible,adminAccess)).andExpect(status().isUnprocessableContent());
        var wrongVolumes=new Correction(1,"Revisão",decimal("11"),decimal("10"),OilCondition.ACCEPTABLE,false,List.of(),null);
        mvc.perform(body(post(COLLECTIONS+id+"/corrections"),wrongVolumes,adminAccess)).andExpect(status().isUnprocessableContent());
        owner.update("UPDATE collection_failure_reason SET status='INACTIVE' WHERE code='VOLUME_OUT_OF_TOLERANCE'");
        try { mvc.perform(body(post(COLLECTIONS+id+"/corrections"),failureCorrection(1),adminAccess)).andExpect(status().isUnprocessableContent()); }
        finally { owner.update("UPDATE collection_failure_reason SET status='ACTIVE' WHERE code='VOLUME_OUT_OF_TOLERANCE'"); }
        assertThat(reads.detail(id,shop.id(),false)).isEqualTo(original);
        assertThat(corrections.history(id,adminId,new HistoryQuery(0,20)).totalElements()).isEqualTo(1);
    }

    @Test void transitionsAndPaginationAreExplicitAndHistoryIsAdminOnly() throws Exception {
        UUID id=operations.registerCollection(ready(),collectionInput("10")).collection().id();
        mvc.perform(body(post(COLLECTIONS+id+"/restorations"),correction(1,"10"),adminAccess)).andExpect(status().isConflict());
        corrections.annul(id,adminId,new Annulment(1,"Revisão"));
        mvc.perform(body(post(COLLECTIONS+id+"/annulments"),new Annulment(2,"Repetido"),adminAccess)).andExpect(status().isConflict());
        mvc.perform(body(post(COLLECTIONS+id+"/corrections"),correction(2,"10"),adminAccess)).andExpect(status().isConflict());
        String route=COLLECTIONS+id+"/calculation-revisions";
        mvc.perform(get(route)).andExpect(status().isUnauthorized());
        mvc.perform(get(route).header("Authorization","Bearer "+shop.access())).andExpect(status().isForbidden());
        mvc.perform(get(route).param("size","1").header("Authorization","Bearer "+adminAccess))
            .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1)).andExpect(jsonPath("$.totalElements").value(2))
            .andExpect(jsonPath("$.items[0].current").value(true));
        mvc.perform(get(route).param("size","101").header("Authorization","Bearer "+adminAccess)).andExpect(status().isBadRequest());
        mvc.perform(get(route).param("page","-1").header("Authorization","Bearer "+adminAccess)).andExpect(status().isBadRequest());
        mvc.perform(get(route).param("page","100").header("Authorization","Bearer "+adminAccess))
            .andExpect(status().isOk()).andExpect(jsonPath("$.items").isEmpty());
        mvc.perform(body(post(COLLECTIONS+UUID.randomUUID()+"/annulments"),new Annulment(1,"Revisão"),adminAccess)).andExpect(status().isNotFound());
        mvc.perform(get(COLLECTIONS+UUID.randomUUID()+"/calculation-revisions").header("Authorization","Bearer "+adminAccess)).andExpect(status().isNotFound());
    }

    @Test void concurrentCorrectionsHaveExactlyOneWinner() throws Exception {
        UUID id=operations.registerCollection(ready(),collectionInput("10")).collection().id();
        var a=correction(1,"9");var b=correction(1,"11");
        var results=parallel(()->attempt(()->corrections.correct(id,adminId,a)),()->attempt(()->corrections.correct(id,adminId,b)));
        assertThat(results).containsExactlyInAnyOrder(true,false);
        assertThat(reads.detail(id,shop.id(),false).collection().revision()).isEqualTo(2);
        assertThat(corrections.history(id,adminId,new HistoryQuery(0,20)).totalElements()).isEqualTo(2);
    }

    @Test void correctionSerializesWithNewCollectionAndRebuildsLaterBalance() throws Exception {
        UUID id=operations.registerCollection(ready(),collectionInput("10")).collection().id();UUID next=ready();
        var revised=failureCorrection(1);var normal=collectionInput("10");
        var results=parallel(()->attempt(()->corrections.correct(id,adminId,revised)),()->attempt(()->operations.registerCollection(next,normal)));
        assertThat(results).containsOnly(true);
        assertThat(reads.score(shop.id()).points()).isEqualTo(60);
        assertThat(countCollections()).isEqualTo(2);
    }

    @Test void recurrenceMovesAndOldCalculationsRemainAvailable() {
        var first=operations.registerCollection(ready(),collectionInput("10"));Detail last=first;
        for(int i=1;i<11;i++) last=operations.registerCollection(ready(),collectionInput("10"));
        assertThat(reads.score(shop.id()).points()).isEqualTo(670);
        corrections.annul(first.collection().id(),adminId,new Annulment(1,"Duplicidade confirmada"));
        assertThat(reads.score(shop.id()).points()).isEqualTo(610);
        var current=reads.detail(last.collection().id(),shop.id(),false);
        assertThat(current.collection().revision()).isEqualTo(1);
        assertThat(current.calculation().components()).contains(new PointComponent("RECURRENCE_BONUS",10));
        assertThat(corrections.history(last.collection().id(),adminId,new HistoryQuery(0,20)).totalElements()).isEqualTo(2);
    }

    @Test void restorationOfOldCyclePreservesItsOriginAndLimitedRole() {
        var original=operations.registerCollection(ready(),collectionInput("10"));UUID id=original.collection().id();
        corrections.annul(id,adminId,new Annulment(1,"Verificação"));
        owner.update("UPDATE establishment_subscription SET status='INACTIVE',inactivated_at=clock_timestamp() WHERE id=?",shop.subscription());
        owner.update("UPDATE subscription_cycle SET ends_at=clock_timestamp()-interval '1 second' WHERE id=?",shop.cycle());
        var result=corrections.restore(id,adminId,correction(2,"10"));
        assertThat(result.collection().requestId()).isEqualTo(original.collection().requestId());
        assertThat(requests.availability(shop.cycle(),shop.id()).consumedVolumeLiters()).isEqualByComparingTo("10");
        assertThat(requests.availability(shop.cycle(),shop.id()).canAcceptNewRequest()).isFalse();
        assertThat(owner.queryForObject("SELECT has_table_privilege('olius_test_api','collection_log','SELECT')",Boolean.class)).isFalse();
        assertThat(owner.queryForObject("SELECT has_table_privilege('olius_test_api','collection','UPDATE')",Boolean.class)).isFalse();
        assertThat(owner.queryForObject("SELECT has_function_privilege('olius_test_api','public.rebuild_user_points(uuid,uuid,text)','EXECUTE')",Boolean.class)).isFalse();
    }

    @Test void certificatesAreReconciledOnAnnulmentAndRestoration() {
        UUID level=UUID.randomUUID();owner.update("INSERT INTO certificate_level(id,name,required_liters) VALUES (?,?,10)",level,"Sprint10-"+level);
        UUID id=operations.registerCollection(ready(),collectionInput("10")).collection().id();
        UUID certificate=owner.queryForObject("SELECT id FROM certificate WHERE establishment_id=? AND certificate_level_id=?",UUID.class,shop.id(),level);
        assertThat(owner.queryForObject("SELECT count(*) FROM certificate WHERE establishment_id=? AND certificate_level_id=? AND status='ACTIVE'",Integer.class,shop.id(),level)).isEqualTo(1);
        corrections.annul(id,adminId,new Annulment(1,"Volume indevido"));
        assertThat(owner.queryForObject("SELECT count(*) FROM certificate WHERE establishment_id=? AND certificate_level_id=? AND status='ACTIVE'",Integer.class,shop.id(),level)).isZero();
        corrections.restore(id,adminId,correction(2,"10"));
        assertThat(owner.queryForObject("SELECT count(*) FROM certificate WHERE establishment_id=? AND certificate_level_id=? AND status='ACTIVE'",Integer.class,shop.id(),level)).isEqualTo(1);
        assertThat(owner.queryForObject("SELECT id FROM certificate WHERE establishment_id=? AND certificate_level_id=?",UUID.class,shop.id(),level)).isEqualTo(certificate);
    }

    @Test void failureAfterOperationalUpdateRollsBackRevisionAuditAndCalculations() throws Exception {
        var original=operations.registerCollection(ready(),collectionInput("10"));UUID id=original.collection().id();
        int logs=owner.queryForObject("SELECT count(*) FROM collection_log WHERE id=?",Integer.class,id);
        owner.execute("""
                CREATE FUNCTION test_support.reject_recalculation() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN RAISE EXCEPTION 'Falha técnica de teste' USING ERRCODE='P0001'; END $$;
                CREATE TRIGGER reject_recalculation BEFORE INSERT ON public.point_calculation
                FOR EACH ROW WHEN (NEW.recalculated_by IS NOT NULL)
                EXECUTE FUNCTION test_support.reject_recalculation()
                """);
        try {
            mvc.perform(body(post(COLLECTIONS+id+"/corrections"),correction(1,"11"),adminAccess))
                .andExpect(status().isInternalServerError());
            assertThat(reads.detail(id,shop.id(),false)).isEqualTo(original);
            assertThat(owner.queryForObject("SELECT count(*) FROM collection_log WHERE id=?",Integer.class,id)).isEqualTo(logs);
            assertThat(corrections.history(id,adminId,new HistoryQuery(0,20)).totalElements()).isEqualTo(1);
        } finally {
            owner.execute("DROP TRIGGER reject_recalculation ON public.point_calculation");
            owner.execute("DROP FUNCTION test_support.reject_recalculation()");
        }
    }

    @Test void openApiDocumentsSecurityAndIndependentCalculationHistory() throws Exception {
        var response=mvc.perform(get("/v3/api-docs")).andExpect(status().isOk()).andReturn();
        var paths=json.readTree(response.getResponse().getContentAsString()).get("paths");
        for(String suffix:List.of("corrections","annulments","restorations")) {
            var operation=paths.get(COLLECTIONS+"{id}/"+suffix).get("post");
            assertThat(operation.get("security").toString()).contains("bearer");
            assertThat(operation.get("responses").has("409")).isTrue();
        }
        assertThat(paths.get(COLLECTIONS+"{id}/calculation-revisions").get("get").get("description").asText()).contains("independentes");
        var operational=paths.get(COLLECTIONS+"{id}/revisions").get("get");
        assertThat(operational.get("security").toString()).contains("bearer");
        assertThat(operational.get("description").asText()).contains("currentCode","snapshot");
    }

    @Test void operationalHistoryPreservesFactsActorsReasonsAndEmptyPageMetadata() throws Exception {
        UUID id=operations.registerCollection(ready(),failure()).collection().id();
        var changed=new Correction(1,"Qualidade conferida",decimal("8"),decimal("10"),OilCondition.UNACCEPTABLE,false,
                List.of("VOLUME_OUT_OF_TOLERANCE","OIL_UNACCEPTABLE"),"Qualidade inadequada");
        corrections.correct(id,adminId,changed);
        corrections.annul(id,adminId,new Annulment(2,"Visita anulada para análise"));
        corrections.restore(id,adminId,correction(3,"10"));
        var history=corrections.operationalHistory(id,adminId,new HistoryQuery(0,20));
        assertThat(history.totalElements()).isEqualTo(4);
        assertThat(history.items()).extracting(br.com.olius.olius_core_api.dto.CollectionOperationalHistoryContracts.OperationalRevision::revision).containsExactly(4,3,2,1);
        assertThat(history.items().get(0).failureReasons()).isEmpty();
        assertThat(history.items().get(1).recordStatus()).isEqualTo(RecordStatus.ANNULLED);
        assertThat(history.items().get(1).failureReasons()).isEmpty();
        assertThat(history.items().get(2).failureReasons()).extracting(br.com.olius.olius_core_api.dto.CollectionOperationalHistoryContracts.HistoricalReason::currentCode)
            .containsExactlyInAnyOrder("VOLUME_OUT_OF_TOLERANCE","OIL_UNACCEPTABLE");
        var original=history.items().get(3);
        assertThat(original.actorKind()).isEqualTo("DRIVER_FORM");assertThat(original.operationalDriverId()).isEqualTo(driverId);
        assertThat(original.performedBy()).isNull();assertThat(original.collectedVolumeLiters()).isZero();
        assertThat(original.recordedAt()).isAfterOrEqualTo(original.collectionDate());
        assertThat(history.items().getFirst().correctedBy()).isEqualTo(adminId);
        assertThat(history.items().getFirst().correctionReason()).isEqualTo("Conferência administrativa");
        assertThat(history.items().getFirst().auditReason()).isEqualTo("Conferência administrativa");
        String route=COLLECTIONS+id+"/revisions";
        mvc.perform(get(route).param("size","2").param("page","1").header("Authorization","Bearer "+adminAccess))
            .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(4))
            .andExpect(jsonPath("$.items[0].revision").value(2)).andExpect(jsonPath("$.items[1].revision").value(1));
        var result=mvc.perform(get(route).header("Authorization","Bearer "+adminAccess)).andExpect(status().isOk()).andReturn();
        assertThat(result.getResponse().getContentAsString()).doesNotContain(access.cpf(),access.cnh(),shop.qr(),"password_hash","snapshot_kind");
        mvc.perform(get(route).param("page","2147483647").param("size","100").header("Authorization","Bearer "+adminAccess))
            .andExpect(status().isOk()).andExpect(jsonPath("$.items").isEmpty()).andExpect(jsonPath("$.totalElements").value(4));
    }

    @Test void operationalHistoryUsesHistoricalIdsAndNullableCurrentCatalogLabels() {
        UUID reason=UUID.randomUUID();String code="HIST_"+reason;
        owner.update("INSERT INTO collection_failure_reason(id,code,name) VALUES (?,?,?)",reason,code,"Motivo histórico");
        var initial=new Record(access,decimal("0"),decimal("0"),OilCondition.NOT_ASSESSED,false,List.of("VOLUME_OUT_OF_TOLERANCE",code),"Sem óleo");
        UUID id=operations.registerCollection(ready(),initial).collection().id();
        corrections.correct(id,adminId,correction(1,"10"));
        owner.update("UPDATE collection_failure_reason SET code=? WHERE id=?","RENAMED_"+reason,reason);
        var labelled=corrections.operationalHistory(id,adminId,new HistoryQuery(0,20)).items().getLast().failureReasons();
        assertThat(labelled).anySatisfy(value->{assertThat(value.id()).isEqualTo(reason);assertThat(value.currentCode()).isEqualTo("RENAMED_"+reason);});
        owner.update("DELETE FROM collection_failure_reason WHERE id=?",reason);
        var unlabelled=corrections.operationalHistory(id,adminId,new HistoryQuery(0,20)).items().getLast().failureReasons();
        assertThat(unlabelled).anySatisfy(value->{assertThat(value.id()).isEqualTo(reason);assertThat(value.currentCode()).isNull();});
    }

    @Test void operationalHistoryChecksAuthorizationValidationMissingResourceAndDatabaseAcl() throws Exception {
        UUID id=operations.registerCollection(ready(),collectionInput("10")).collection().id();String route=COLLECTIONS+id+"/revisions";
        mvc.perform(get(route)).andExpect(status().isUnauthorized());
        mvc.perform(get(route).param("adminId",adminId.toString()).header("Authorization","Bearer "+shop.access())).andExpect(status().isForbidden());
        mvc.perform(get(route).param("page","-1").header("Authorization","Bearer "+adminAccess)).andExpect(status().isBadRequest());
        mvc.perform(get(route).param("size","101").header("Authorization","Bearer "+adminAccess)).andExpect(status().isBadRequest());
        mvc.perform(get(COLLECTIONS+UUID.randomUUID()+"/revisions").header("Authorization","Bearer "+adminAccess)).andExpect(status().isNotFound());
        assertThat(owner.queryForObject("SELECT has_table_privilege('olius_test_api','collection_log','SELECT')",Boolean.class)).isFalse();
        assertThat(owner.queryForObject("SELECT has_table_privilege('olius_test_api','collection_failure_log','SELECT')",Boolean.class)).isFalse();
        assertThat(owner.queryForObject("SELECT prosecdef AND provolatile='s' AND proowner='olius_business_owner'::regrole FROM pg_proc WHERE oid='public.get_collection_revision_history(uuid,uuid,integer,integer)'::regprocedure",Boolean.class)).isTrue();
        owner.execute("REVOKE EXECUTE ON FUNCTION public.get_collection_revision_history(uuid,uuid,integer,integer) FROM olius_api");
        try { mvc.perform(get(route).header("Authorization","Bearer "+adminAccess)).andExpect(status().isInternalServerError()); }
        finally { owner.execute("GRANT EXECUTE ON FUNCTION public.get_collection_revision_history(uuid,uuid,integer,integer) TO olius_api"); }
    }

    @Test void absentAuditNeverBecomesAnEmptyHistoryOrFallbackToCurrentState() throws Exception {
        UUID id=operations.registerCollection(ready(),collectionInput("10")).collection().id();
        owner.update("DELETE FROM collection_log WHERE id=?",id);
        mvc.perform(get(COLLECTIONS+id+"/revisions").header("Authorization","Bearer "+adminAccess))
            .andExpect(status().isInternalServerError()).andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));
        assertThat(reads.detail(id,shop.id(),false).collection().revision()).isEqualTo(1);
    }

    @Test void operationalReadPreservesAuditContextAndRepeatableSnapshotDuringCorrection() throws Exception {
        UUID id=operations.registerCollection(ready(),collectionInput("10")).collection().id();
        var transaction=new org.springframework.transaction.support.TransactionTemplate(transactions);
        transaction.setReadOnly(true);transaction.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
        transaction.executeWithoutResult(status->{
            apiJdbc.queryForObject("SELECT set_config('app.audit_actor','EXTERNAL',true)",String.class);
            apiJdbc.queryForObject("SELECT set_config('app.current_user_id','not-a-uuid',true)",String.class);
            assertThat(corrections.operationalHistory(id,adminId,new HistoryQuery(0,20)).totalElements()).isEqualTo(1);
            var input=correction(1,"11");
            CompletableFuture.supplyAsync(()->corrections.correct(id,adminId,input)).orTimeout(30,TimeUnit.SECONDS).join();
            assertThat(corrections.operationalHistory(id,adminId,new HistoryQuery(0,20)).totalElements()).isEqualTo(1);
            assertThat(apiJdbc.queryForObject("SELECT current_setting('app.audit_actor')",String.class)).isEqualTo("EXTERNAL");
            assertThat(apiJdbc.queryForObject("SELECT current_setting('app.current_user_id')",String.class)).isEqualTo("not-a-uuid");
        });
        assertThat(corrections.operationalHistory(id,adminId,new HistoryQuery(0,20)).totalElements()).isEqualTo(2);
    }

    private Correction correction(int revision,String volume) {
        return new Correction(revision,"Conferência administrativa",decimal(volume),decimal(volume),OilCondition.ACCEPTABLE,false,List.of(),null);
    }
    private Correction failureCorrection(int revision) {
        return new Correction(revision,"Falha confirmada",decimal("0"),decimal("0"),OilCondition.NOT_ASSESSED,false,List.of("VOLUME_OUT_OF_TOLERANCE"),"Sem óleo");
    }
    private Record failure() { return new Record(access,decimal("0"),decimal("0"),OilCondition.NOT_ASSESSED,false,List.of("VOLUME_OUT_OF_TOLERANCE"),"Sem óleo"); }
    private UUID readyFor(String volume) {
        var created=requests.create(shop.id(),new CollectionRequestContracts.Create(decimal(volume),null),UUID.randomUUID());
        requests.schedule(created.id(),adminId,new CollectionRequestContracts.Scheduling(Instant.now().plusSeconds(86400),Instant.now().minusSeconds(1),"Acordado"));
        operations.arrival(created.id(),access);operations.accept(created.id(),shop.id());return created.id();
    }
    private UUID scheduled() {
        var created=requests.create(shop.id(),new CollectionRequestContracts.Create(decimal("10"),null),UUID.randomUUID());
        requests.schedule(created.id(),adminId,new CollectionRequestContracts.Scheduling(Instant.now().plusSeconds(86400),Instant.now().minusSeconds(1),"Acordado externamente"));return created.id();
    }
    private UUID ready() { UUID id=scheduled();operations.arrival(id,access);operations.accept(id,shop.id());return id; }
    private Record collectionInput(String liters) { return new Record(access,decimal(liters),decimal(liters),OilCondition.ACCEPTABLE,false,List.of(),null); }
    private BigDecimal decimal(String value) { return new BigDecimal(value); }
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
