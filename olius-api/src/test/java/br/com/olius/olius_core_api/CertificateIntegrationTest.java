package br.com.olius.olius_core_api;

import br.com.olius.olius_core_api.dto.CollectionContracts.*;
import br.com.olius.olius_core_api.dto.CollectionContracts.Record;
import br.com.olius.olius_core_api.dto.CollectionContracts.Period;
import br.com.olius.olius_core_api.dto.CollectionRequestContracts;
import br.com.olius.olius_core_api.entity.enums.*;
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
import org.testcontainers.junit.jupiter.*;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@Testcontainers @AutoConfigureMockMvc
@SpringBootTest(properties={"spring.config.import=","olius.security.ephemeral-keys=true",
    "olius.security.auth-requests-per-minute=10000","olius.admin-invitations.email.enabled=false",
    "olius.payments.stripe.enabled=false","springdoc.api-docs.enabled=true"})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CertificateIntegrationTest {
    @Container static final OfficialPostgres POSTGRES=new OfficialPostgres();
    private static final String PASSWORD="Password1!";
    @DynamicPropertySource static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url",POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username",()->OfficialPostgres.API_USER);
        properties.add("spring.datasource.password",()->OfficialPostgres.API_PASSWORD);
    }
    @Autowired CollectionOperationsService operations;
    @Autowired CollectionRequestService requests;
    @Autowired CertificateReadService certificates;
    @Autowired EnvironmentalReadService environmental;
    @Autowired CollectionCorrectionService corrections;
    @Autowired DeliveryService deliveries;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    br.com.olius.olius_core_api.repository.CertificateReadRepository certificateRepository;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    br.com.olius.olius_core_api.repository.DeliveryRepository deliveryRepository;
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
    @Test @Order(1) void missingCatalogPreservesVolumeAndReadingNeverIssuesACertificate() throws Exception {
        operations.registerCollection(ready(),collectionInput("10"));
        var initial=certificates.progress(shop.id(),false,null);
        assertThat(initial.levels()).isEmpty();assertThat(certificates.levels(shop.id(),false)).isEmpty();assertThat(initial.eligibleVolumeLiters()).isEqualByComparingTo("10");
        UUID level=level("10");
        getOk("/api/v1/certificate-levels",shop.access());getOk("/api/v1/certificate-levels",adminAccess);
        var progress=progress(level);assertThat(progress.reached()).isTrue();assertThat(progress.certificateId()).isNull();
        assertThat(certificates.list(shop.id(),false,null,query(null,level,0,20)).totalElements()).isZero();
        getOk("/api/v1/establishments/me/certificate-progress",shop.access());
        assertThat(owner.queryForObject("SELECT count(*) FROM certificate WHERE establishment_id=?",Integer.class,shop.id())).isZero();
    }
    @Test void thresholdsUseActualAccumulatedVolumeAndIncludeUnsuccessfulCollections() throws Exception {
        UUID bronze=level("100"),silver=level("500"),gold=level("1000");
        operations.registerCollection(ready("99.99"),collectionInput("99.99"));
        assertThat(progress(bronze).reached()).isFalse();assertThat(progress(bronze).remainingLiters()).isEqualByComparingTo("0.01");
        operations.registerCollection(ready(),new Record(access,decimal("0.01"),decimal("1"),OilCondition.ACCEPTABLE,false,List.of("VOLUME_OUT_OF_TOLERANCE"),null));
        assertThat(progress(bronze).reached()).isTrue();assertThat(progress(bronze).certificateId()).isNotNull();
        operations.registerCollection(ready("400"),collectionInput("400"));
        assertThat(progress(silver).reached()).isTrue();assertThat(progress(gold).reached()).isFalse();
        operations.registerCollection(ready("500.01"),collectionInput("500.01"));
        assertThat(progress(gold).reached()).isTrue();
        assertThat(certificates.progress(shop.id(),false,null).eligibleVolumeLiters()).isEqualByComparingTo("1000.01");
    }
    @Test void annulmentAndRestorationKeepCertificateIdentityAndExposeAuthoritativeStatus() throws Exception {
        UUID level=level("10");var collection=operations.registerCollection(ready(),collectionInput("10")).collection();
        UUID id=progress(level).certificateId();var original=certificates.detail(id,shop.id(),false);
        corrections.annul(collection.id(),adminId,new br.com.olius.olius_core_api.dto.CollectionCorrectionContracts.Annulment(1,"Registro indevido"));
        var revoked=certificates.detail(id,shop.id(),false);
        assertThat(revoked.status()).isEqualTo(CertificateStatus.REVOKED);assertThat(revoked.revokedAt()).isNotNull();
        assertThat(progress(level).remainingLiters()).isEqualByComparingTo("10");
        corrections.restore(collection.id(),adminId,new br.com.olius.olius_core_api.dto.CollectionCorrectionContracts.Correction(
                2,"Conferência confirmou a coleta",decimal("10"),decimal("10"),OilCondition.ACCEPTABLE,false,List.of(),null));
        var restored=certificates.detail(id,adminId,true);
        assertThat(restored.status()).isEqualTo(CertificateStatus.ACTIVE);
        assertThat(restored.code()).isEqualTo(original.code());assertThat(restored.issuedAt()).isEqualTo(original.issuedAt());
        assertThat(restored.revokedAt()).isEqualTo(revoked.revokedAt());assertThat(restored.reactivatedAt()).isNotNull();
        assertThat(certificates.list(shop.id(),false,null,query(CertificateStatus.REVOKED,level,0,20)).items()).isEmpty();
    }
    @Test void ownershipRolesAndInactiveAccountsAreEnforcedWithoutASubscriptionGate() throws Exception {
        UUID level=level("10");operations.registerCollection(ready(),collectionInput("10"));UUID id=progress(level).certificateId();
        Shop other=shop();
        mvc.perform(get("/api/v1/certificates/"+id).header("Authorization","Bearer "+other.access())).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/admin/certificates").header("Authorization","Bearer "+shop.access())).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/certificate-levels")).andExpect(status().isUnauthorized());
        UUID citizen=UUID.randomUUID();String email=citizen+"@test.invalid";
        owner.update("INSERT INTO users(id,name,email,password_hash,user_type) VALUES (?,'Cidadão',?,?,'CITIZENS')",citizen,email,hash);
        mvc.perform(get("/api/v1/certificate-levels").header("Authorization","Bearer "+login(email))).andExpect(status().isForbidden());
        owner.update("UPDATE establishment_subscription SET status='INACTIVE',inactivated_at=clock_timestamp() WHERE id=?",shop.subscription());
        getOk("/api/v1/certificates/"+id,shop.access());getOk("/api/v1/establishments/me/certificate-progress",shop.access());
        getOk("/api/v1/establishments/me/environmental-summary",shop.access());
        getOk("/api/v1/certificates/"+id,adminAccess);
        assertThatThrownBy(()->certificates.detail(id,other.id(),true)).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        assertThatThrownBy(()->environmental.summary(citizen,false,new Period(null,null))).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        owner.update("UPDATE users SET status='INACTIVE' WHERE id=?",other.id());
        assertThatThrownBy(()->certificates.progress(other.id(),false,null)).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        mvc.perform(get("/api/v1/certificate-levels").header("Authorization","Bearer "+other.access())).andExpect(status().isUnauthorized());
    }
    @Test void paginationFilteringValidationAndMissingTargetsHaveExplicitContracts() throws Exception {
        UUID level=level("10");operations.registerCollection(ready(),collectionInput("10"));
        var page=certificates.list(adminId,true,shop.id(),query(CertificateStatus.ACTIVE,level,0,1));
        assertThat(page.totalElements()).isEqualTo(1);assertThat(page.items()).hasSize(1);
        assertThat(certificates.list(adminId,true,shop.id(),query(null,level,1,1)).items()).isEmpty();
        var issued=page.items().getFirst().issuedAt();
        var excluded=new br.com.olius.olius_core_api.dto.CertificateContracts.Query(null,level,null,issued,0,20);
        assertThat(certificates.list(shop.id(),false,null,excluded).items()).isEmpty();
        mvc.perform(get("/api/v1/certificates").param("size","101").header("Authorization","Bearer "+shop.access())).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/certificates").param("status","FAKE").header("Authorization","Bearer "+shop.access())).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/certificates").param("issuedFrom","2026-12-01T00:00:00Z").param("issuedUntil","2026-01-01T00:00:00Z")
                .header("Authorization","Bearer "+shop.access())).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/admin/establishments/"+UUID.randomUUID()+"/certificate-progress").header("Authorization","Bearer "+adminAccess)).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/certificates/"+UUID.randomUUID()).header("Authorization","Bearer "+adminAccess)).andExpect(status().isNotFound());
        getOk("/api/v1/admin/certificates?establishmentId="+shop.id(),adminAccess);
        getOk("/api/v1/admin/establishments/"+shop.id()+"/certificate-progress",adminAccess);
    }
    @Test void physicalPeriodAndCurrentRecordsDefineSummaryWithoutReservationsOrForfeitures() throws Exception {
        var first=operations.registerCollection(ready(),collectionInput("10")).collection();
        operations.registerCollection(ready(),new Record(access,decimal("8"),decimal("10"),OilCondition.ACCEPTABLE,false,List.of("VOLUME_OUT_OF_TOLERANCE"),null));
        var last=operations.registerCollection(ready("5"),collectionInput("5")).collection();
        scheduled(); // Reserva não é volume coletado.
        var summary=environmental.summary(shop.id(),false,new Period(null,null));
        assertThat(summary.collections().count()).isEqualTo(3);assertThat(summary.collections().collectedVolumeLiters()).isEqualByComparingTo("23");
        assertThat(summary.collections().successful()).isEqualTo(2);assertThat(summary.collections().unsuccessful()).isEqualTo(1);assertThat(summary.deliveries()).isNull();
        assertThat(environmental.summary(shop.id(),false,new Period(null,first.collectionDate())).collections().count()).isZero();
        assertThat(environmental.summary(shop.id(),false,new Period(last.collectionDate(),null)).collections().count()).isEqualTo(1);
        corrections.annul(last.id(),adminId,new br.com.olius.olius_core_api.dto.CollectionCorrectionContracts.Annulment(1,"Coleta anulada"));
        assertThat(environmental.summary(shop.id(),false,new Period(null,null)).collections().collectedVolumeLiters()).isEqualByComparingTo("18");
        mvc.perform(get("/api/v1/admin/environmental-summary").param("from","2026-12-01T00:00:00Z").param("until","2026-01-01T00:00:00Z")
                .header("Authorization","Bearer "+adminAccess)).andExpect(status().isBadRequest());
        getOk("/api/v1/admin/environmental-summary",adminAccess);
    }
    @Test void metadataUsesCurrentCatalogAndDoesNotExposePdfOrAuditInternals() throws Exception {
        UUID level=level("10");operations.registerCollection(ready(),collectionInput("10"));UUID id=progress(level).certificateId();
        owner.update("UPDATE certificate_level SET description='Descrição atual' WHERE id=?",level);
        mvc.perform(get("/api/v1/certificates/"+id).header("Authorization","Bearer "+shop.access()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.level.description").value("Descrição atual"))
            .andExpect(jsonPath("$.pdfUrl").doesNotExist()).andExpect(jsonPath("$.passwordHash").doesNotExist());
        var api=mvc.perform(get("/v3/api-docs")).andExpect(status().isOk()).andReturn();
        var paths=json.readTree(api.getResponse().getContentAsString()).get("paths");
        assertThat(paths.get("/api/v1/certificates").get("get").get("description").asText()).contains("assinatura");
        assertThat(paths.get("/api/v1/admin/environmental-summary").get("get").get("description").asText()).contains("B2C");
    }
    @Test void b2cDeliveriesRemainSeparateFromB2bAndEnvironmentalGoals() throws Exception {
        UUID citizen=UUID.randomUUID(),pev=UUID.randomUUID(),address=UUID.randomUUID();String qr="Q"+UUID.randomUUID().toString().replace("-","")+"1234567890";
        owner.update("INSERT INTO users(id,name,email,password_hash,user_type) VALUES (?,'Cidadão',?,?,'CITIZENS')",citizen,citizen+"@test.invalid",hash);
        owner.update("INSERT INTO user_qr_code(user_id,qr_token,user_type) VALUES (?,?,'CITIZENS')",citizen,qr);
        owner.update("INSERT INTO citizens(id,cpf,qr_token) VALUES (?,?,?)",citizen,document(),qr);
        owner.update("INSERT INTO addresses(id,owner_kind,state,city,neighborhood,street,number,cep) VALUES (?,'PEV','SP','Cidade','Bairro','Rua','1','00000000')",address);
        owner.update("INSERT INTO pev(id,establishment_id,address_id) VALUES (?,?,?)",pev,shop.id(),address);
        owner.update("UPDATE pev SET status='APPROVED',approved_by=?,approved_at=clock_timestamp() WHERE id=?",adminId,pev);
        owner.update("UPDATE establishment SET is_pev=true WHERE id=?",shop.id());
        var before=environmental.summary(adminId,true,new Period(null,null));
        Instant physical=Instant.now().minusSeconds(60).truncatedTo(ChronoUnit.MICROS);
        deliveries.register(pev,shop.id(),UserType.ESTABLISHMENT,new br.com.olius.olius_core_api.dto.DeliveryContracts.Record(UUID.randomUUID(),qr,decimal("3.70"),physical));
        var after=environmental.summary(adminId,true,new Period(null,null));
        assertThat(after.deliveries().count()).isEqualTo(before.deliveries().count()+1);
        assertThat(after.deliveries().acceptedVolumeLiters()).isEqualByComparingTo(before.deliveries().acceptedVolumeLiters().add(decimal("3.70")));
        assertThat(after.collections()).isEqualTo(before.collections());
        assertThat(environmental.summary(shop.id(),false,new Period(null,null)).collections().count()).isZero();
        assertThat(certificates.progress(shop.id(),false,null).eligibleVolumeLiters()).isEqualByComparingTo("0");
        assertThat(environmental.summary(adminId,true,new Period(physical,physical.plusSeconds(1))).deliveries().count()).isEqualTo(1);
        assertThat(environmental.summary(adminId,true,new Period(null,physical)).deliveries().count()).isEqualTo(before.deliveries().count());
        // Outra transação confirma uma entrega entre a página e a contagem.
        org.mockito.Mockito.doAnswer(call->{
            Object page=call.callRealMethod();
            committed(()->deliveries.register(pev,shop.id(),UserType.ESTABLISHMENT,
                new br.com.olius.olius_core_api.dto.DeliveryContracts.Record(UUID.randomUUID(),qr,decimal("1"),physical.plusSeconds(2))));
            return page;
        }).when(deliveryRepository).pageForPev(pev,20,0L);
        var snapshot=deliveries.listForPev(pev,shop.id(),UserType.ESTABLISHMENT,0,20);
        assertThat(snapshot.items()).hasSize(1);assertThat(snapshot.totalElements()).isEqualTo(1);
        org.mockito.Mockito.reset(deliveryRepository);
        assertThat(deliveries.listForPev(pev,shop.id(),UserType.ESTABLISHMENT,0,20).totalElements()).isEqualTo(2);
    }
    @Test void concurrentCertificateIssuanceDoesNotMixReadSnapshots() throws Exception {
        UUID level=level("10"),request=ready();
        org.mockito.Mockito.doAnswer(call->{
            committed(()->operations.registerCollection(request,collectionInput("10")));
            return call.callRealMethod();
        }).when(certificateRepository).list(org.mockito.ArgumentMatchers.eq(shop.id()),org.mockito.ArgumentMatchers.any());
        var snapshot=certificates.list(shop.id(),false,null,query(null,level,0,20));
        assertThat(snapshot.items()).isEmpty();assertThat(snapshot.totalElements()).isZero();
        org.mockito.Mockito.reset(certificateRepository);
        assertThat(certificates.list(shop.id(),false,null,query(null,level,0,20)).totalElements()).isEqualTo(1);
    }
    @Test void coreRoleCannotReadCertificateLogsOrInvokeInternalReconciliation() {
        var restricted=new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(),OfficialPostgres.API_USER,OfficialPostgres.API_PASSWORD));
        assertThat(restricted.queryForObject("SELECT has_table_privilege(current_user,'public.certificate_log','SELECT')",Boolean.class)).isFalse();
        assertThat(restricted.queryForObject("SELECT has_function_privilege(current_user,'public.reconcile_establishment_certificates(uuid)','EXECUTE')",Boolean.class)).isFalse();
        assertThat(restricted.queryForObject("SELECT has_table_privilege(current_user,'public.certificate','INSERT')",Boolean.class)).isFalse();
    }
    private <T> T committed(Callable<T> action) throws Exception {
        try(var pool=Executors.newSingleThreadExecutor()) { return pool.submit(action).get(30,TimeUnit.SECONDS); }
    }
    private UUID level(String liters) {
        UUID id=UUID.randomUUID();owner.update("INSERT INTO certificate_level(id,name,required_liters) VALUES (?,?,?)",id,"Nível teste-"+id,decimal(liters));return id;
    }
    private br.com.olius.olius_core_api.dto.CertificateContracts.LevelProgress progress(UUID level) {
        return certificates.progress(shop.id(),false,null).levels().stream().filter(p->p.levelId().equals(level)).findFirst().orElseThrow();
    }
    private br.com.olius.olius_core_api.dto.CertificateContracts.Query query(CertificateStatus status,UUID level,int page,int size) {
        return new br.com.olius.olius_core_api.dto.CertificateContracts.Query(status,level,null,null,page,size);
    }
    private void getOk(String path,String token) throws Exception {
        mvc.perform(get(path).header("Authorization","Bearer "+token)).andExpect(status().isOk());
    }
    private UUID scheduled() { return scheduled("10"); }
    private UUID scheduled(String estimate) {
        var created=requests.create(shop.id(),new CollectionRequestContracts.Create(decimal(estimate),null),UUID.randomUUID());
        requests.schedule(created.id(),adminId,new CollectionRequestContracts.Scheduling(Instant.now().plusSeconds(86400),Instant.now().minusSeconds(1),"Acordado externamente"));return created.id();
    }
    private UUID ready() { return ready("10"); }
    private UUID ready(String estimate) { UUID id=scheduled(estimate);operations.arrival(id,access);operations.accept(id,shop.id());return id; }
    private Record collectionInput(String liters) { return new Record(access,decimal(liters),decimal(liters),OilCondition.ACCEPTABLE,false,List.of(),null); }
    private BigDecimal decimal(String value) { return new BigDecimal(value); }
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
