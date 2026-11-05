package br.com.olius.olius_core_api;

import br.com.olius.olius_core_api.dto.AddressReplacement;
import br.com.olius.olius_core_api.dto.IdentityRequests.AddressInput;
import br.com.olius.olius_core_api.dto.ProfileUpdate;
import br.com.olius.olius_core_api.dto.CollectionRequestContracts.Create;
import br.com.olius.olius_core_api.entity.enums.UserType;
import br.com.olius.olius_core_api.exception.BusinessRuleException;
import br.com.olius.olius_core_api.service.EstablishmentAddressService;
import br.com.olius.olius_core_api.service.CollectionRequestService;
import br.com.olius.olius_core_api.service.ProfileUpdateService;
import br.com.olius.olius_core_api.testfixture.OfficialPostgres;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Timestamp;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@Testcontainers @AutoConfigureMockMvc
@SpringBootTest(properties={"spring.config.import=","olius.security.ephemeral-keys=true",
    "olius.security.auth-requests-per-minute=10000","springdoc.api-docs.enabled=true",
    "olius.security.allowed-origins=https://frontend.test"})
class ProfileAddressIntegrationTest {
    @Container static final OfficialPostgres POSTGRES=new OfficialPostgres();
    private static final String PASSWORD="Password1!";
    private static final String PROFILE="/api/v1/users/me";
    private static final String ADDRESS="/api/v1/establishments/me/address";
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username",() -> OfficialPostgres.API_USER);
        registry.add("spring.datasource.password",() -> OfficialPostgres.API_PASSWORD);
    }
    @BeforeAll static void faultProbes() {
        owner().execute("""
            CREATE FUNCTION test_support.fail_phone() RETURNS trigger LANGUAGE plpgsql AS $$
            BEGIN
                IF NEW.telephone='0000000000' THEN RAISE EXCEPTION 'Fixture write failure' USING ERRCODE='P7001'; END IF;
                RETURN NEW;
            END $$;
            CREATE TRIGGER test_phone_failure BEFORE INSERT ON public.telephone
                FOR EACH ROW EXECUTE FUNCTION test_support.fail_phone();
            CREATE TABLE test_support.fail_pev(id uuid PRIMARY KEY);
            CREATE FUNCTION test_support.fail_pev_review() RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER
                SET search_path=pg_catalog,pg_temp AS $$
            BEGIN
                IF NEW.status='PENDING' AND EXISTS(SELECT 1 FROM test_support.fail_pev WHERE id=NEW.id) THEN
                    RAISE EXCEPTION 'Fixture review failure' USING ERRCODE='P7001';
                END IF;
                RETURN NEW;
            END $$;
            CREATE TRIGGER test_pev_review_failure BEFORE UPDATE ON public.pev
                FOR EACH ROW EXECUTE FUNCTION test_support.fail_pev_review();
            """);
    }
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired PasswordEncoder passwords;
    @Autowired ProfileUpdateService profiles;
    @Autowired br.com.olius.olius_core_api.service.PevService pevs;
    @Autowired EstablishmentAddressService addresses;
    @Autowired CollectionRequestService requests;
    @Autowired JdbcTemplate limited;
    @Autowired PlatformTransactionManager transactions;

    @ParameterizedTest @ValueSource(strings={"CITIZENS","ESTABLISHMENT","ADMIN"})
    void eachProfileCanEditItselfWithoutChangingCredentialsOrRetainedPhones(String role) throws Exception {
        Account account=account(role);
        UUID retained=owner().queryForObject("SELECT id FROM telephone WHERE user_id=?",UUID.class,account.id());
        String hash=owner().queryForObject("SELECT password_hash FROM users WHERE id=?",String.class,account.id());
        send(patch(PROFILE),account,Map.of("name","  Nome atualizado  ","telephones",List.of("11999999999","11888888888")))
            .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
            .andExpect(jsonPath("$.name").value("Nome atualizado")).andExpect(jsonPath("$.email").value(account.email()))
            .andExpect(jsonPath("$.userType").value(role)).andExpect(jsonPath("$.status").value("ACTIVE"));
        assertThat(owner().queryForObject("SELECT id FROM telephone WHERE user_id=? AND telephone='11999999999'",UUID.class,account.id())).isEqualTo(retained);
        assertThat(owner().queryForObject("SELECT password_hash FROM users WHERE id=?",String.class,account.id())).isEqualTo(hash);
        mvc.perform(get("/api/v1/auth/session").header("Authorization","Bearer "+account.access())).andExpect(status().isOk());
        assertThat(owner().queryForObject("SELECT bool_and(actor_kind='USER' AND performed_by=id) FROM users_log WHERE id=? AND operation='UPDATE'",Boolean.class,account.id())).isTrue();
    }
    @Test void omittedFieldsPreserveValuesAndEmptyTelephoneListClearsThem() throws Exception {
        Account account=account("CITIZENS");
        send(patch(PROFILE),account,Map.of("name","Novo nome")).andExpect(status().isOk()).andExpect(jsonPath("$.telephones[0]").value("11999999999"));
        send(patch(PROFILE),account,Map.of("telephones",List.of())).andExpect(status().isOk())
            .andExpect(jsonPath("$.name").value("Novo nome")).andExpect(jsonPath("$.telephones").isEmpty());
        Account another=account("CITIZENS");
        send(patch(PROFILE),account,Map.of("telephones",List.of("11999999999"))).andExpect(status().isOk());
        assertThat(owner().queryForObject("SELECT count(*) FROM telephone WHERE user_id IN (?,?) AND telephone='11999999999'",Integer.class,account.id(),another.id())).isEqualTo(2);
    }
    @ParameterizedTest @ValueSource(strings={"{}","{\"name\":null}","{\"name\":\"   \"}",
        "{\"telephones\":null}","{\"telephones\":[null]}","{\"telephones\":[\"          \"]}",
        "{\"telephones\":[\"123\"]}","{\"telephones\":[\"11999999999\",\"11999999999\"]}"})
    void invalidProfilePayloadNeverChangesStoredValues(String body) throws Exception {
        Account account=account("CITIZENS");
        mvc.perform(patch(PROFILE).header("Authorization","Bearer "+account.access()).contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isBadRequest());
        assertOriginalProfile(account);
    }
    @ParameterizedTest @ValueSource(strings={"email","password","passwordHash","cpf","cnpj","address","userType","status","points","qrToken","id","namePresent","telephonesPresent"})
    void protectedAndUnknownProfileFieldsAreRejected(String field) throws Exception {
        Account account=account("CITIZENS");
        send(patch(PROFILE),account,Map.of("name","Não aplicar",field,"untrusted"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
        assertOriginalProfile(account);
    }
    @Test void sameProfilePreservesTelephoneIdsAndDoesNotCreateNameAudit() throws Exception {
        Account account=account("CITIZENS");
        UUID phone=owner().queryForObject("SELECT id FROM telephone WHERE user_id=?",UUID.class,account.id());
        send(patch(PROFILE),account,Map.of("name","Titular","telephones",List.of("11999999999"))).andExpect(status().isOk());
        assertThat(owner().queryForObject("SELECT id FROM telephone WHERE user_id=?",UUID.class,account.id())).isEqualTo(phone);
        assertThat(owner().queryForObject("SELECT count(*) FROM users_log WHERE id=? AND operation='UPDATE'",Integer.class,account.id())).isZero();
    }
    @Test void phoneSqlFailureRollsBackNameAndRemovalOfOldPhones() throws Exception {
        Account account=account("CITIZENS");
        send(patch(PROFILE),account,Map.of("name","Não confirmar","telephones",List.of("0000000000"))).andExpect(status().isInternalServerError());
        assertOriginalProfile(account);
        assertThat(owner().queryForObject("SELECT count(*) FROM users_log WHERE id=? AND operation='UPDATE'",Integer.class,account.id())).isZero();
    }
    @Test void concurrentPhoneReplacementsLeaveOneCompleteSet() throws Exception {
        Account account=account("CITIZENS");var start=new CountDownLatch(1);
        var one=new ProfileUpdate();one.setTelephones(List.of("1111111111","2222222222"));
        var two=new ProfileUpdate();two.setTelephones(List.of("3333333333","4444444444"));
        try(var executor=Executors.newFixedThreadPool(2)) {
            var first=executor.submit(() -> { start.await();return profiles.update(account.id(),UserType.CITIZENS,one); });
            var second=executor.submit(() -> { start.await();return profiles.update(account.id(),UserType.CITIZENS,two); });
            start.countDown();first.get(20,TimeUnit.SECONDS);second.get(20,TimeUnit.SECONDS);
        }
        List<String> stored=owner().queryForList("SELECT telephone FROM telephone WHERE user_id=? ORDER BY telephone",String.class,account.id());
        assertThat(stored.equals(one.getTelephones()) || stored.equals(two.getTelephones())).isTrue();
    }
    @Test void missingAuthenticationAndInactiveSessionsCannotEdit() throws Exception {
        mvc.perform(patch(PROFILE).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Novo\"}")).andExpect(status().isUnauthorized());
        Account account=account("CITIZENS");owner().update("UPDATE users SET status='INACTIVE' WHERE id=?",account.id());
        send(patch(PROFILE),account,Map.of("name","Não aplicar")).andExpect(status().isUnauthorized());assertOriginalProfile(account);
    }
    @Test void commercialAddressNeedsNoSubscriptionAndNeverChangesPevAddress() throws Exception {
        Account company=account("ESTABLISHMENT");UUID pev=pev(company,"APPROVED");
        UUID commercial=owner().queryForObject("SELECT address_id FROM establishment WHERE id=?",UUID.class,company.id());
        UUID ownPev=owner().queryForObject("SELECT address_id FROM pev WHERE id=?",UUID.class,pev);
        var replacement=postal("Rua comercial nova");replacement.remove("latitude");replacement.remove("longitude");replacement.remove("complement");
        send(put(ADDRESS),company,Map.of("address",replacement)).andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(commercial.toString())).andExpect(jsonPath("$.street").value("Rua comercial nova"))
            .andExpect(jsonPath("$.latitude").isEmpty()).andExpect(jsonPath("$.longitude").isEmpty()).andExpect(jsonPath("$.complement").isEmpty());
        assertThat(owner().queryForObject("SELECT street FROM addresses WHERE id=?",String.class,ownPev)).isEqualTo("Rua do PEV");
        assertThat(owner().queryForObject("SELECT status::text FROM pev WHERE id=?",String.class,pev)).isEqualTo("APPROVED");
    }
    @Test void addressEndpointRequiresEstablishmentAndCompleteFields() throws Exception {
        for(String role:List.of("CITIZENS","ADMIN")) send(put(ADDRESS),account(role),Map.of("address",postal("Rua nova"))).andExpect(status().isForbidden());
        mvc.perform(put(ADDRESS).contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isUnauthorized());
        Account company=account("ESTABLISHMENT");
        var missing=postal("Rua nova");missing.remove("city");send(put(ADDRESS),company,Map.of("address",missing)).andExpect(status().isBadRequest());
        var partial=postal("Rua nova");partial.remove("longitude");send(put(ADDRESS),company,Map.of("address",partial)).andExpect(status().isBadRequest());
        var precise=postal("Rua nova");precise.put("latitude",new BigDecimal("-23.1234567"));send(put(ADDRESS),company,Map.of("address",precise)).andExpect(status().isBadRequest());
        var extra=postal("Rua nova");extra.put("ownerId",UUID.randomUUID());send(put(ADDRESS),company,Map.of("address",extra)).andExpect(status().isBadRequest());
        assertThat(owner().queryForObject("SELECT a.street FROM addresses a JOIN establishment e ON e.address_id=a.id WHERE e.id=?",String.class,company.id())).isEqualTo("Rua comercial");
    }
    @Test void pendingOrApprovedUnattendedRequestBlocksAddressChangeButNoopIsAllowed() throws Exception {
        Account company=account("ESTABLISHMENT");cycle(company);UUID request=createRequest(company);Account admin=account("ADMIN");
        for(String state:List.of("PENDING","APPROVED")) {
            if(state.equals("APPROVED")) schedule(request,admin);
            send(put(ADDRESS),company,Map.of("address",postal("Outro local"))).andExpect(status().isConflict());
            send(put(ADDRESS),company,Map.of("address",postal("Rua comercial"))).andExpect(status().isOk());
        }
    }
    @ParameterizedTest @ValueSource(strings={"REJECTED","CANCELLED"})
    void terminalUnattendedRequestDoesNotBlockAddressChange(String state) throws Exception {
        Account company=account("ESTABLISHMENT");cycle(company);UUID request=createRequest(company);
        if(state.equals("REJECTED")) send(post("/api/v1/admin/collection-requests/"+request+"/rejection"),account("ADMIN"),Map.of("reason","Sem atendimento")).andExpect(status().isOk());
        else send(post("/api/v1/collection-requests/"+request+"/cancellation"),company,Map.of("reason","Cadastro incorreto")).andExpect(status().isOk());
        send(put(ADDRESS),company,Map.of("address",postal("Novo local"))).andExpect(status().isOk());
    }
    @Test void completedCollectionDoesNotBlockCommercialAddressMaintenance() throws Exception {
        Account company=account("ESTABLISHMENT");cycle(company);UUID request=createRequest(company);Account admin=account("ADMIN");schedule(request,admin);
        UUID driver=UUID.randomUUID();String document=document(11);owner().update("INSERT INTO driver(id,name,cpf,cnh) VALUES (?,'Fixture',?,?)",driver,document,document);
        owner().queryForObject("SELECT record_collection_arrival(?,?)",Timestamp.class,request,driver);
        owner().queryForObject("SELECT accept_collection_service(?,?)",Timestamp.class,request,company.id());
        owner().queryForObject("CALL record_collection(?,?,10,10,'ACCEPTABLE',false,ARRAY[]::text[],NULL,clock_timestamp(),NULL)",UUID.class,request,driver);
        send(put(ADDRESS),company,Map.of("address",postal("Local posterior"))).andExpect(status().isOk());
        assertThat(owner().queryForObject("SELECT count(*) FROM collection WHERE collection_request_id=?",Integer.class,request)).isEqualTo(1);
    }
    /** A criação que confirma primeiro torna o pedido visível após a espera no mesmo lock. */
    @Test void addressWaitsForConcurrentNewRequestAndThenRejectsTheMove() throws Exception {
        Account company=account("ESTABLISHMENT");cycle(company);
        try(Connection blocker=ownerSource().getConnection()) {
            blocker.setAutoCommit(false);
            try(var statement=blocker.prepareStatement("CALL create_collection_request(?,10,NULL,?,NULL)")) {
                statement.setObject(1,company.id());statement.setObject(2,UUID.randomUUID());statement.execute();
            }
            int pid=backend(blocker);
            try(var executor=Executors.newSingleThreadExecutor()) {
                var next=new AddressReplacement(input("Local bloqueado"));
                var result=executor.submit(() -> {
                    try { addresses.replace(company.id(),next);return true; }
                    catch(BusinessRuleException conflict) { return false; }
                });
                try { waitForBlock(pid);blocker.commit();assertThat(result.get(15,TimeUnit.SECONDS)).isFalse(); }
                finally { blocker.rollback(); }
            }
        }
        assertThat(owner().queryForObject("SELECT a.street FROM addresses a JOIN establishment e ON e.address_id=a.id WHERE e.id=?",String.class,company.id())).isEqualTo("Rua comercial");
    }
    /** Se a mudança confirma primeiro, uma nova solicitação passa a usar o cadastro já atualizado. */
    @Test void concurrentRequestWaitsForCommercialAddressCommit() throws Exception {
        Account company=account("ESTABLISHMENT");cycle(company);
        var updated=new CountDownLatch(1);var release=new CountDownLatch(1);var pid=new java.util.concurrent.atomic.AtomicInteger();
        try(var executor=Executors.newFixedThreadPool(2)) {
            var edit=executor.submit(() -> new TransactionTemplate(transactions).execute(status -> {
                var result=addresses.replace(company.id(),new AddressReplacement(input("Novo cadastro")));
                pid.set(limited.queryForObject("SELECT pg_backend_pid()",Integer.class));updated.countDown();
                try { if(!release.await(15,TimeUnit.SECONDS)) throw new IllegalStateException("Fixture timeout"); }
                catch(InterruptedException ex) { Thread.currentThread().interrupt();throw new IllegalStateException(ex); }
                return result;
            }));
            assertThat(updated.await(10,TimeUnit.SECONDS)).isTrue();
            var creation=executor.submit(() -> requests.create(company.id(),new Create(new BigDecimal("10"),null),UUID.randomUUID()));
            try { waitForBlock(pid.get());release.countDown();edit.get(15,TimeUnit.SECONDS);assertThat(creation.get(15,TimeUnit.SECONDS).establishmentId()).isEqualTo(company.id()); }
            finally { release.countDown(); }
        }
        assertThat(owner().queryForObject("SELECT a.street FROM addresses a JOIN establishment e ON e.address_id=a.id WHERE e.id=?",String.class,company.id())).isEqualTo("Novo cadastro");
    }
    @Test void approvedPevAddressChangeLeavesMapAndRequiresNewApproval() throws Exception {
        Account company=account("ESTABLISHMENT");UUID pev=pev(company,"APPROVED");
        Timestamp approval=owner().queryForObject("SELECT approved_at FROM pev WHERE id=?",Timestamp.class,pev);
        send(patch("/api/v1/pevs/me/address"),company,Map.of("address",postal("Novo PEV"))).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PENDING"));
        assertThat(publicItem(pev)).isEmpty();assertThat(owner().queryForObject("SELECT is_pev FROM establishment WHERE id=?",Boolean.class,company.id())).isFalse();
        assertThat(owner().queryForObject("SELECT approved_at FROM pev WHERE id=?",Timestamp.class,pev)).isEqualTo(approval);
        send(patch("/api/v1/admin/pevs/"+pev+"/status"),account("ADMIN"),Map.of("status","APPROVED")).andExpect(status().isOk());assertThat(publicItem(pev)).isPresent();
    }
    @Test void equivalentPevAddressDoesNotWithdrawApprovedPointOrCreateAddressAudit() throws Exception {
        Account company=account("ESTABLISHMENT");UUID pev=pev(company,"APPROVED");UUID address=owner().queryForObject("SELECT address_id FROM pev WHERE id=?",UUID.class,pev);
        var same=postal("Rua do PEV");same.put("latitude",new BigDecimal("-23.5505000"));
        send(patch("/api/v1/pevs/me/address"),company,Map.of("address",same)).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPROVED"));
        assertThat(owner().queryForObject("SELECT count(*) FROM addresses_log WHERE id=? AND operation='UPDATE'",Integer.class,address)).isZero();
    }
    @Test void addressAndReviewRollbackTogetherWhenPevWriteFails() throws Exception {
        Account company=account("ESTABLISHMENT");UUID pev=pev(company,"APPROVED");owner().update("INSERT INTO test_support.fail_pev(id) VALUES (?)",pev);
        send(patch("/api/v1/pevs/me/address"),company,Map.of("address",postal("Não confirmar"))).andExpect(status().isInternalServerError());
        assertThat(owner().queryForObject("SELECT status::text FROM pev WHERE id=?",String.class,pev)).isEqualTo("APPROVED");
        assertThat(owner().queryForObject("SELECT a.street FROM addresses a JOIN pev p ON p.address_id=a.id WHERE p.id=?",String.class,pev)).isEqualTo("Rua do PEV");
        assertThat(owner().queryForObject("SELECT is_pev FROM establishment WHERE id=?",Boolean.class,company.id())).isTrue();
    }
    @Test void publicPevProjectionOmitsComplementAndCitizenNameButPrivateViewsRetainComplement() throws Exception {
        Account citizen=account("CITIZENS"),company=account("ESTABLISHMENT");UUID first=pev(citizen,"APPROVED"),second=pev(company,"APPROVED");
        JsonNode individual=publicItem(first).orElseThrow(),business=publicItem(second).orElseThrow();
        assertThat(individual.has("name")).isTrue();assertThat(individual.get("name").isNull()).isTrue();assertThat(business.path("name").asText()).isEqualTo("Titular");
        assertThat(individual.path("address").has("complement")).isFalse();assertThat(business.path("address").has("complement")).isFalse();
        mvc.perform(get("/api/v1/pevs/me").header("Authorization","Bearer "+company.access())).andExpect(status().isOk()).andExpect(jsonPath("$.address.complement").value("Portão lateral"));
        Account admin=account("ADMIN");mvc.perform(get("/api/v1/admin/pevs/"+second).header("Authorization","Bearer "+admin.access())).andExpect(status().isOk()).andExpect(jsonPath("$.address.complement").value("Portão lateral"));
        send(patch(PROFILE),company,Map.of("name","Nome comercial novo")).andExpect(status().isOk());assertThat(publicItem(second).orElseThrow().path("name").asText()).isEqualTo("Nome comercial novo");
    }
    @Test void corsAllowsPutAndOpenApiDocumentsTheNewContracts() throws Exception {
        mvc.perform(options(ADDRESS).header("Origin","https://frontend.test").header("Access-Control-Request-Method","PUT")
            .header("Access-Control-Request-Headers","authorization,content-type")).andExpect(status().isOk());
        var doc=json.readTree(mvc.perform(get("/v3/api-docs")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(doc.path("paths").path(PROFILE).has("patch")).isTrue();assertThat(doc.path("paths").path(ADDRESS).has("put")).isTrue();
        assertThat(doc.path("components").path("schemas").path("PublicAddress").path("properties").has("complement")).isFalse();
        assertThat(doc.path("components").path("schemas").path("AddressView").path("properties").has("complement")).isTrue();
    }

    /** Uma entrega que já detém o lock confirma antes da revisão, sem perder pontos ou histórico. */
    @Test void deliveryInProgressCommitsBeforeAddressReviewWithoutLosingHistory() throws Exception {
        Account company=account("ESTABLISHMENT"),beneficiary=account("CITIZENS");
        UUID pev=pev(company,"APPROVED"),delivery;
        try(Connection blocker=ownerSource().getConnection()) {
            blocker.setAutoCommit(false);
            try(var statement=blocker.prepareStatement("CALL record_pev_delivery(?,?,?,?,2.50,clock_timestamp(),NULL)")) {
                statement.setObject(1,UUID.randomUUID());statement.setObject(2,beneficiary.id());
                statement.setObject(3,pev);statement.setObject(4,company.id());
                try(var result=statement.executeQuery()) { result.next();delivery=result.getObject(1,UUID.class); }
            }
            int pid=backend(blocker);
            try(var executor=Executors.newSingleThreadExecutor()) {
                var input=new br.com.olius.olius_core_api.dto.PevRequests.UpdateAddress(input("PEV revisado"));
                var edit=executor.submit(() -> pevs.updateAddress(input,company.id(),UserType.ESTABLISHMENT));
                try { waitForBlock(pid);blocker.commit();assertThat(edit.get(15,TimeUnit.SECONDS).status().name()).isEqualTo("PENDING"); }
                finally { blocker.rollback(); }
            }
        }
        assertThat(owner().queryForObject("SELECT points FROM citizens WHERE id=?",Long.class,beneficiary.id()))
            .isEqualTo(owner().queryForObject("SELECT points_earned FROM delivery_pev WHERE id=?",Long.class,delivery));
        assertThat(owner().queryForObject("SELECT record_status::text FROM delivery_pev WHERE id=?",String.class,delivery)).isEqualTo("RECORDED");
        mvc.perform(get("/api/v1/pevs/"+pev+"/deliveries").header("Authorization","Bearer "+company.access()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].id").value(delivery.toString()));
        assertThat(publicItem(pev)).isEmpty();
    }

    private ResultActions send(MockHttpServletRequestBuilder request,Account account,Object body) throws Exception {
        return mvc.perform(request.header("Authorization","Bearer "+account.access()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));
    }
    private void assertOriginalProfile(Account account) {
        assertThat(owner().queryForObject("SELECT name FROM users WHERE id=?",String.class,account.id())).isEqualTo("Titular");
        assertThat(owner().queryForList("SELECT telephone FROM telephone WHERE user_id=?",String.class,account.id())).containsExactly("11999999999");
    }
    private Account account(String role) throws Exception {
        String email=UUID.randomUUID()+"@example.invalid";UUID id;
        if(role.equals("ADMIN")) {
            id=owner().queryForObject("INSERT INTO users(name,email,password_hash,user_type) VALUES ('Titular',?,?,'ADMIN') RETURNING id",UUID.class,email,passwords.encode(PASSWORD));
            owner().update("INSERT INTO telephone(id,user_id,telephone) VALUES (?,?,?)",UUID.randomUUID(),id,"11999999999");
        } else {
            var body=new LinkedHashMap<String,Object>();body.put("name","Titular");body.put("email",email);body.put("password",PASSWORD);body.put("telephones",List.of("11999999999"));String path;
            if(role.equals("CITIZENS")) { body.put("cpf",document(11));path="citizens"; }
            else {
                UUID type=UUID.randomUUID();owner().update("INSERT INTO establishment_type(id,name) VALUES (?,?)",type,"Tipo-"+type);
                body.put("cnpj",document(14));body.put("typeId",type);body.put("address",postal("Rua comercial"));path="establishments";
            }
            var response=mvc.perform(post("/api/v1/auth/registrations/"+path).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
                .andExpect(status().isCreated()).andReturn();id=UUID.fromString(json.readTree(response.getResponse().getContentAsString()).path("id").asText());
        }
        var response=mvc.perform(post("/api/v1/auth/mobile/login").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("email",email,"password",PASSWORD)))).andExpect(status().isOk()).andReturn();
        return new Account(id,email,json.readTree(response.getResponse().getContentAsString()).path("accessToken").asText());
    }
    private UUID pev(Account account,String state) throws Exception {
        var response=send(post("/api/v1/pevs"),account,Map.of("address",postal("Rua do PEV"))).andExpect(status().isCreated()).andReturn();
        UUID id=UUID.fromString(json.readTree(response.getResponse().getContentAsString()).path("id").asText());
        if(!state.equals("PENDING")) {
            Account admin=account("ADMIN");
            if(state.equals("REJECTED")) owner().update("UPDATE pev SET status='REJECTED' WHERE id=?",id);
            else owner().update("UPDATE pev SET status=?::approval_status_t,approved_by=?,approved_at=clock_timestamp() WHERE id=?",state,admin.id(),id);
        }
        return id;
    }
    private Optional<JsonNode> publicItem(UUID id) throws Exception {
        var response=mvc.perform(get("/api/v1/pevs").param("size","100")).andExpect(status().isOk()).andReturn();
        for(var item:json.readTree(response.getResponse().getContentAsString()).path("items"))
            if(item.path("id").asText().equals(id.toString())) return Optional.of(item);
        return Optional.empty();
    }
    private Map<String,Object> postal(String street) {
        var address=new LinkedHashMap<String,Object>();address.put("state","SP");address.put("city","Cidade");address.put("neighborhood","Bairro");address.put("street",street);address.put("number","10");address.put("cep","01001000");address.put("complement","Portão lateral");
        address.put("latitude",new BigDecimal("-23.5505"));address.put("longitude",new BigDecimal("-46.6333"));return address;
    }
    private AddressInput input(String street) { return new AddressInput("SP","Cidade","Bairro",street,"10","01001000","Portão lateral",new BigDecimal("-23.5505"),new BigDecimal("-46.6333")); }
    private void cycle(Account company) {
        UUID sub=UUID.randomUUID(),plan=UUID.randomUUID(),cycle=UUID.randomUUID();
        owner().update("INSERT INTO establishment_subscription(id,establishment_id,status,created_at,activated_at) VALUES (?,?,'ACTIVE',CURRENT_TIMESTAMP-interval '2 days',CURRENT_TIMESTAMP-interval '2 days')",sub,company.id());
        owner().update("INSERT INTO subscription_plan(id,name,monthly_price,volume_limit_liters,collection_limit) VALUES (?,?,19.90,100,10)",plan,"Plano-"+plan);
        var start=ZonedDateTime.now(ZoneId.of("America/Sao_Paulo")).minusDays(1).truncatedTo(ChronoUnit.SECONDS);
        owner().update("""
            INSERT INTO subscription_cycle(id,subscription_id,establishment_id,cycle_number,starts_at,ends_at,
                anchor_day,anchor_local_time,anchor_timezone,plan_id,plan_name,monthly_price,volume_limit_liters,collection_limit)
            VALUES (?,?,?,1,?,?,?,?,'America/Sao_Paulo',?,'Plano fixture',19.90,100,10)
            """,cycle,sub,company.id(),Timestamp.from(start.toInstant()),Timestamp.from(start.plusMonths(1).toInstant()),
            start.getDayOfMonth(),java.sql.Time.valueOf(start.toLocalTime()),plan);
    }
    private UUID createRequest(Account company) throws Exception {
        var response=mvc.perform(post("/api/v1/collection-requests").header("Authorization","Bearer "+company.access()).header("Idempotency-Key",UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON).content("{\"estimatedVolumeLiters\":10}")).andExpect(status().isCreated()).andReturn();
        return UUID.fromString(json.readTree(response.getResponse().getContentAsString()).path("id").asText());
    }
    private void schedule(UUID request,Account admin) throws Exception {
        Instant now=Instant.now();send(post("/api/v1/admin/collection-requests/"+request+"/scheduling"),admin,
            Map.of("scheduledAt",now.plusSeconds(3600).toString(),"agreedAt",now.toString(),"reason","Acordado")).andExpect(status().isOk());
    }
    private static String document(int digits) { return digits==11 ? Long.toString(ThreadLocalRandom.current().nextLong(10000000000L,99999999999L)) : Long.toString(ThreadLocalRandom.current().nextLong(10000000000000L,99999999999999L)); }
    private static int backend(Connection connection) throws Exception {
        try(var statement=connection.createStatement();var result=statement.executeQuery("SELECT pg_backend_pid()")) { result.next();return result.getInt(1); }
    }
    private static void waitForBlock(int pid) {
        await().atMost(Duration.ofSeconds(10)).until(() -> owner().queryForObject("SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE ? = ANY(pg_blocking_pids(pid)))",Boolean.class,pid));
    }
    private static DriverManagerDataSource ownerSource() { return new DriverManagerDataSource(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword()); }
    private static JdbcTemplate owner() { return new JdbcTemplate(ownerSource()); }
    private record Account(UUID id,String email,String access) { @Override public String toString() { return "Account[redacted]"; } }
}
