package br.com.olius.olius_core_api;

import br.com.olius.olius_core_api.testfixture.OfficialPostgres;
import br.com.olius.olius_core_api.entity.enums.UserType;
import br.com.olius.olius_core_api.exception.ResourceNotFoundException;
import br.com.olius.olius_core_api.service.QrIdentificationService;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@Testcontainers @AutoConfigureMockMvc
@SpringBootTest(properties={"spring.config.import=", "springdoc.api-docs.enabled=true",
    "olius.security.ephemeral-keys=true",
    "olius.security.auth-requests-per-minute=10000"})
class PevIntegrationTest {
    @Container static final OfficialPostgres POSTGRES=new OfficialPostgres();
    @DynamicPropertySource static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url",POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username",() -> OfficialPostgres.API_USER);
        properties.add("spring.datasource.password",() -> OfficialPostgres.API_PASSWORD);
    }
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired QrIdentificationService qrIdentification;
    private static final String PASSWORD="Password1!";

    @Test void qrIdentificationRequiresApprovedPevAndKeepsTheTokenUnchanged() throws Exception {
        var validator=citizen();
        var beneficiary=citizen();
        String qr=admin().queryForObject("SELECT qr_token FROM user_qr_code WHERE user_id=?",
            String.class,beneficiary.id());
        assertThatThrownBy(() -> qrIdentification.identify(qr,validator.id(),UserType.CITIZENS))
            .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);

        String access=login(validator.email());
        mvc.perform(post("/api/v1/pevs").header("Authorization","Bearer "+access)
            .contentType(MediaType.APPLICATION_JSON).content(request())).andExpect(status().isCreated());
        assertThatThrownBy(() -> qrIdentification.identify(qr,validator.id(),UserType.CITIZENS))
            .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);

        UUID adminId=UUID.randomUUID();
        admin().update("INSERT INTO users(id,name,email,password_hash,user_type) VALUES (?,'Admin',?,'fixture-only','ADMIN')",
            adminId,email());
        admin().update("UPDATE pev SET status='APPROVED',approved_by=?,approved_at=clock_timestamp() WHERE citizen_id=?",
            adminId,validator.id());
        assertThat(qrIdentification.identify(qr,validator.id(),UserType.CITIZENS))
            .extracting(QrIdentificationService.IdentifiedCitizen::id,
                QrIdentificationService.IdentifiedCitizen::name)
            .containsExactly(beneficiary.id(),"Cidadão");
        assertThat(qrIdentification.identify(qr,validator.id(),UserType.CITIZENS).id())
            .isEqualTo(beneficiary.id());
        assertThat(admin().queryForObject("SELECT qr_token FROM user_qr_code WHERE user_id=?",
            String.class,beneficiary.id())).isEqualTo(qr);

        assertThatThrownBy(() -> qrIdentification.identify("invalid",validator.id(),UserType.CITIZENS))
            .isInstanceOf(ResourceNotFoundException.class);
        String ownQr=admin().queryForObject("SELECT qr_token FROM user_qr_code WHERE user_id=?",
            String.class,validator.id());
        assertThatThrownBy(() -> qrIdentification.identify(ownQr,validator.id(),UserType.CITIZENS))
            .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        admin().update("UPDATE users SET status='INACTIVE' WHERE id=?",beneficiary.id());
        assertThatThrownBy(() -> qrIdentification.identify(qr,validator.id(),UserType.CITIZENS))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test void openApiShowsPublicListingAndAuthenticatedOwnerOperations() throws Exception {
        var response=mvc.perform(get("/v3/api-docs")).andExpect(status().isOk()).andReturn();
        var paths=json.readTree(response.getResponse().getContentAsString()).get("paths");
        var pevs=paths.get("/api/v1/pevs");
        assertThat(pevs.get("get").get("security").isEmpty()).isTrue();
        assertThat(pevs.get("post").get("security").toString()).contains("bearer");
        assertThat(paths.get("/api/v1/pevs/me").get("get").get("security").toString())
            .contains("bearer");
        assertThat(pevs.get("get").get("responses").has("200")).isTrue();
        assertThat(pevs.get("post").get("responses").has("201")).isTrue();
    }

    @Test void citizenRequestsOwnPevAndDuplicateDoesNotLeaveAnAddress() throws Exception {
        var account=citizen(); String access=login(account.email());
        mvc.perform(get("/api/v1/pevs/me").header("Authorization","Bearer "+access))
            .andExpect(status().isNotFound());
        var created=mvc.perform(post("/api/v1/pevs").header("Authorization","Bearer "+access)
            .contentType(MediaType.APPLICATION_JSON).content(request()))
            .andExpect(status().isCreated()).andExpect(header().string("Location","/api/v1/pevs/me"))
            .andExpect(jsonPath("$.status").value("PENDING"))
            .andExpect(jsonPath("$.address.street").value("Rua do PEV")).andReturn();
        UUID pevId=UUID.fromString(json.readTree(created.getResponse().getContentAsString()).get("id").asText());
        assertThat(admin().queryForObject("SELECT count(*) FROM pev p JOIN addresses a ON a.id=p.address_id WHERE p.id=? AND p.citizen_id=? AND p.establishment_id IS NULL AND a.owner_kind='PEV'",Integer.class,pevId,account.id())).isEqualTo(1);
        assertThat(admin().queryForObject("SELECT count(*) FROM pev_log WHERE id=? AND performed_by=?",Integer.class,pevId,account.id())).isEqualTo(1);
        long addresses=admin().queryForObject("SELECT count(*) FROM addresses",Long.class);
        mvc.perform(post("/api/v1/pevs").header("Authorization","Bearer "+access)
            .contentType(MediaType.APPLICATION_JSON).content(request()))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PEV_ALREADY_EXISTS"));
        assertThat(admin().queryForObject("SELECT count(*) FROM addresses",Long.class)).isEqualTo(addresses);
        mvc.perform(get("/api/v1/pevs/me").header("Authorization","Bearer "+access))
            .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(pevId.toString()))
            .andExpect(jsonPath("$.address.state").value("SP"));
    }

    @Test void establishmentPevHasAddressSeparateFromCommercialAddress() throws Exception {
        UUID type=UUID.randomUUID(); admin().update("INSERT INTO establishment_type(id,name) VALUES (?,?)",type,"Tipo-"+type);
        String email=email();
        var registered=mvc.perform(post("/api/v1/auth/registrations/establishments")
            .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of(
                "name","Estabelecimento", "email",email,"password",PASSWORD,
                "cnpj",digits(14),"typeId",type,"address",commercialAddress()))))
            .andExpect(status().isCreated()).andReturn();
        UUID owner=UUID.fromString(json.readTree(registered.getResponse().getContentAsString()).get("id").asText());
        String access=login(email);
        mvc.perform(post("/api/v1/pevs").header("Authorization","Bearer "+access)
            .contentType(MediaType.APPLICATION_JSON).content(request()))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("PENDING"));
        assertThat(admin().queryForObject("SELECT count(*) FROM pev p JOIN establishment e ON e.id=p.establishment_id JOIN addresses a ON a.id=p.address_id WHERE p.establishment_id=? AND p.citizen_id IS NULL AND p.address_id<>e.address_id AND a.owner_kind='PEV'",Integer.class,owner)).isEqualTo(1);
        assertThat(admin().queryForObject("SELECT is_pev FROM establishment WHERE id=?",Boolean.class,owner)).isFalse();
    }

    @Test void publicListPaginatesApprovedPevsInStableOrderWithoutOwnerData() throws Exception {
        List<UUID> ids=new ArrayList<>();
        for(int i=0;i<4;i++) {
            var account=citizen(); String access=login(account.email());
            var created=mvc.perform(post("/api/v1/pevs").header("Authorization","Bearer "+access)
                .contentType(MediaType.APPLICATION_JSON).content(request()))
                .andExpect(status().isCreated()).andReturn();
            ids.add(UUID.fromString(json.readTree(created.getResponse().getContentAsString()).get("id").asText()));
        }
        mvc.perform(get("/api/v1/pevs")).andExpect(status().isOk())
            .andExpect(jsonPath("$.totalElements").value(0));
        UUID adminId=UUID.randomUUID();
        admin().update("INSERT INTO users(id,name,email,password_hash,user_type) VALUES (?,'Admin',?,'fixture-only','ADMIN')",adminId,email());
        List<UUID> approved=ids.subList(0,3).stream().sorted(Comparator.comparing(UUID::toString)).toList();
        UUID earliest=approved.get(2);
        admin().update("UPDATE pev SET status='APPROVED',created_at=TIMESTAMPTZ '2020-01-01 00:00:00+00',approved_at=clock_timestamp(),approved_by=? WHERE id=?",adminId,earliest);
        for(UUID id:approved.subList(0,2))
            admin().update("UPDATE pev SET status='APPROVED',created_at=TIMESTAMPTZ '2020-01-02 00:00:00+00',approved_at=clock_timestamp(),approved_by=? WHERE id=?",adminId,id);
        mvc.perform(get("/api/v1/pevs")).andExpect(status().isOk())
            .andExpect(jsonPath("$.page").value(0))
            .andExpect(jsonPath("$.size").value(20))
            .andExpect(jsonPath("$.totalElements").value(3))
            .andExpect(jsonPath("$.items.length()").value(3))
            .andExpect(jsonPath("$.items[0].id").value(earliest.toString()))
            .andExpect(jsonPath("$.items[1].id").value(approved.get(0).toString()))
            .andExpect(jsonPath("$.items[2].id").value(approved.get(1).toString()))
            .andExpect(jsonPath("$.items[0].address.street").value("Rua do PEV"))
            .andExpect(jsonPath("$.items[0].address.latitude").value(-23.5505))
            .andExpect(jsonPath("$.items[0].address.longitude").value(-46.6333))
            .andExpect(jsonPath("$.items[0].citizenId").doesNotExist())
            .andExpect(jsonPath("$.items[0].email").doesNotExist());
        var first=mvc.perform(get("/api/v1/pevs").param("page","0").param("size","2"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.page").value(0))
            .andExpect(jsonPath("$.size").value(2)).andExpect(jsonPath("$.totalElements").value(3))
            .andExpect(jsonPath("$.items.length()").value(2)).andReturn();
        var second=mvc.perform(get("/api/v1/pevs").param("page","1").param("size","2"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.page").value(1))
            .andExpect(jsonPath("$.size").value(2)).andExpect(jsonPath("$.totalElements").value(3))
            .andExpect(jsonPath("$.items.length()").value(1)).andReturn();
        var firstItems=json.readTree(first.getResponse().getContentAsString()).get("items");
        var secondItems=json.readTree(second.getResponse().getContentAsString()).get("items");
        assertThat(List.of(firstItems.get(0).get("id").asText(),firstItems.get(1).get("id").asText(),
            secondItems.get(0).get("id").asText())).containsExactly(
                earliest.toString(),approved.get(0).toString(),approved.get(1).toString()).doesNotHaveDuplicates();
        mvc.perform(get("/api/v1/pevs").param("size","101")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/pevs").param("size","0")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/pevs").param("page","")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/pevs").param("size","")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/pevs").param("page","-1")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/pevs").param("page","2147483647").param("size","20"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
    }

    @Test void requestRequiresAuthenticationAndRejectsUnknownFields() throws Exception {
        mvc.perform(post("/api/v1/pevs").contentType(MediaType.APPLICATION_JSON).content(request()))
            .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/pevs/me")).andExpect(status().isUnauthorized());
        var account=citizen(); String access=login(account.email());
        for(String field:List.of("\"citizenId\":\""+account.id()+"\"",
            "\"status\":\"APPROVED\"", "\"approvedBy\":\""+UUID.randomUUID()+"\""))
            mvc.perform(post("/api/v1/pevs").header("Authorization","Bearer "+access)
                .contentType(MediaType.APPLICATION_JSON).content(request().replaceFirst("\\}$",","+field+"}")))
                .andExpect(status().isBadRequest());
        assertThat(admin().queryForObject("SELECT count(*) FROM pev WHERE citizen_id=?",Long.class,account.id()))
            .isZero();
        UUID adminId=UUID.randomUUID(); String adminEmail=email();
        var encoder=new org.springframework.security.crypto.argon2.Argon2PasswordEncoder(16,32,1,19456,2);
        admin().update("INSERT INTO users(id,name,email,password_hash,user_type) VALUES (?,'Admin',?,?,'ADMIN')",
            adminId,adminEmail,encoder.encode(PASSWORD));
        String adminAccess=login(adminEmail);
        mvc.perform(post("/api/v1/pevs").header("Authorization","Bearer "+adminAccess)
            .contentType(MediaType.APPLICATION_JSON).content(request())).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/pevs/me").header("Authorization","Bearer "+adminAccess))
            .andExpect(status().isForbidden());
    }

    @Test void inactiveOwnerCannotUsePreviousTokenToRequestOrConsultPev() throws Exception {
        var account=citizen(); String access=login(account.email());
        admin().update("UPDATE users SET status='INACTIVE' WHERE id=?",account.id());
        mvc.perform(post("/api/v1/pevs").header("Authorization","Bearer "+access)
            .contentType(MediaType.APPLICATION_JSON).content(request())).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/pevs/me").header("Authorization","Bearer "+access))
            .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/pevs")).andExpect(status().isOk());
        assertThat(admin().queryForObject("SELECT count(*) FROM pev WHERE citizen_id=?",Long.class,account.id()))
            .isZero();
    }

    @Test void concurrentRequestsCreateOnlyOnePevAndOneAddress() throws Exception {
        var account=citizen(); String access=login(account.email());
        long before=admin().queryForObject("SELECT count(*) FROM addresses",Long.class);
        var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var attempt= (java.util.concurrent.Callable<Integer>) () -> {
                start.await();
                return mvc.perform(post("/api/v1/pevs").header("Authorization","Bearer "+access)
                    .contentType(MediaType.APPLICATION_JSON).content(request()))
                    .andReturn().getResponse().getStatus();
            };
            var first=pool.submit(attempt);
            var second=pool.submit(attempt);
            start.countDown();
            assertThat(first.get(30,TimeUnit.SECONDS)).isIn(201,409);
            assertThat(java.util.List.of(first.get(),second.get())).containsExactlyInAnyOrder(201,409);
        }
        assertThat(admin().queryForObject("SELECT count(*) FROM pev WHERE citizen_id=?",Long.class,account.id())).isEqualTo(1);
        assertThat(admin().queryForObject("SELECT count(*) FROM addresses",Long.class)).isEqualTo(before+1);
    }

    private Account citizen() throws Exception {
        String email=email();
        var response=mvc.perform(post("/api/v1/auth/registrations/citizens")
            .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of(
                "name","Cidadão","email",email,"password",PASSWORD,"cpf",digits(11)))))
            .andExpect(status().isCreated()).andReturn();
        return new Account(UUID.fromString(json.readTree(response.getResponse().getContentAsString()).get("id").asText()),email);
    }

    private String login(String email) throws Exception {
        var response=mvc.perform(post("/api/v1/auth/mobile/login")
            .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of(
                "email",email,"password",PASSWORD))))
            .andExpect(status().isOk()).andReturn();
        return json.readTree(response.getResponse().getContentAsString()).get("accessToken").asText();
    }

    private String request() throws Exception {
        return json.writeValueAsString(Map.of("address",Map.of("state","SP","city","Cidade",
            "neighborhood","Bairro","street","Rua do PEV","number","10","cep","01001000",
            "latitude",new java.math.BigDecimal("-23.5505"),
            "longitude",new java.math.BigDecimal("-46.6333"))));
    }

    private Map<String,String> commercialAddress() {
        return Map.of("state","SP","city","Cidade","neighborhood","Bairro",
            "street","Rua comercial","number","1","cep","01001000");
    }

    private JdbcTemplate admin() {
        return new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword()));
    }

    private String email() { return UUID.randomUUID()+"@test.invalid"; }
    private String digits(int length) { return new java.math.BigInteger(UUID.randomUUID().toString().replace("-",""),16).toString().substring(0,length); }
    private record Account(UUID id,String email) { }
}
