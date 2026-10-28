package br.com.olius.olius_core_api;

import br.com.olius.olius_core_api.testfixture.OfficialPostgres;
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
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@Testcontainers @AutoConfigureMockMvc
@SpringBootTest(properties={"spring.config.import=", "olius.security.ephemeral-keys=true",
    "olius.security.auth-requests-per-minute=10000"})
class PevStatusIntegrationTest {
    @Container static final OfficialPostgres POSTGRES=new OfficialPostgres();
    @DynamicPropertySource static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url",POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username",() -> OfficialPostgres.API_USER);
        properties.add("spring.datasource.password",() -> OfficialPostgres.API_PASSWORD);
    }
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    private static final String PASSWORD="Password1!";

    @Test void approvalInactivationAndReactivationSyncEstablishmentAndPreserveOriginalApproval() throws Exception {
        UUID pev=establishmentPev();
        UUID owner=admin().queryForObject("SELECT establishment_id FROM pev WHERE id=?",UUID.class,pev);
        var first=administrator(); String firstAccess=login(first.email());
        var second=administrator(); String secondAccess=login(second.email());
        assertThat(isPev(owner)).isFalse();

        var approved=mvc.perform(change(pev,"APPROVED",firstAccess))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPROVED"))
            .andExpect(jsonPath("$.approvedBy").value(first.id().toString()))
            .andExpect(jsonPath("$.approvedAt").isNotEmpty()).andReturn();
        String approvedAt=json.readTree(approved.getResponse().getContentAsString()).get("approvedAt").asText();
        assertThat(isPev(owner)).isTrue();
        assertThat(auditUpdates(pev,first.id())).isEqualTo(2);

        mvc.perform(change(pev,"INACTIVE",secondAccess))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("INACTIVE"))
            .andExpect(jsonPath("$.approvedBy").value(first.id().toString()))
            .andExpect(jsonPath("$.approvedAt").value(approvedAt));
        assertThat(isPev(owner)).isFalse();
        mvc.perform(change(pev,"APPROVED",secondAccess))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPROVED"))
            .andExpect(jsonPath("$.approvedBy").value(first.id().toString()))
            .andExpect(jsonPath("$.approvedAt").value(approvedAt));
        assertThat(isPev(owner)).isTrue();
        assertThat(auditUpdates(pev,second.id())).isEqualTo(4);
    }

    @Test void rejectionCannotBeResubmittedByAdministrator() throws Exception {
        UUID pev=citizenPev(); var adminAccount=administrator();
        String access=login(adminAccount.email());
        mvc.perform(change(pev,"REJECTED",access))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REJECTED"))
            .andExpect(jsonPath("$.approvedAt").doesNotExist())
            .andExpect(jsonPath("$.approvedBy").doesNotExist());
        for(String target:List.of("PENDING","APPROVED","REJECTED"))
            mvc.perform(change(pev,target,access)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BUSINESS_STATE_CONFLICT"));
        assertThat(admin().queryForObject("SELECT status::text FROM pev WHERE id=?",String.class,pev))
            .isEqualTo("REJECTED");
        assertThat(auditUpdates(pev,adminAccount.id())).isEqualTo(2);
    }

    @Test void invalidStateAndUnauthorizedCallsDoNotChangePev() throws Exception {
        UUID pev=citizenPev();
        mvc.perform(patch("/api/v1/admin/pevs/{id}/status",pev)
            .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"APPROVED\"}"))
            .andExpect(status().isUnauthorized());
        var citizen=citizen(); String citizenAccess=login(citizen.email());
        mvc.perform(change(pev,"APPROVED",citizenAccess)).andExpect(status().isForbidden());
        var adminAccount=administrator(); String adminAccess=login(adminAccount.email());
        mvc.perform(change(UUID.randomUUID(),"APPROVED",adminAccess))
            .andExpect(status().isNotFound());
        for(String target:List.of("PENDING","INACTIVE"))
            mvc.perform(change(pev,target,adminAccess)).andExpect(status().isConflict());
        mvc.perform(patch("/api/v1/admin/pevs/{id}/status",pev)
            .header("Authorization","Bearer "+adminAccess)
            .contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isBadRequest());
        mvc.perform(patch("/api/v1/admin/pevs/{id}/status",pev)
            .header("Authorization","Bearer "+adminAccess)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"status\":\"APPROVED\",\"approvedBy\":\""+adminAccount.id()+"\"}"))
            .andExpect(status().isBadRequest());
        assertThat(admin().queryForObject("SELECT status::text FROM pev WHERE id=?",String.class,pev))
            .isEqualTo("PENDING");
        assertThat(auditUpdates(pev,adminAccount.id())).isZero();
        admin().queryForObject("SELECT public.set_admin_status(?,?,'INACTIVE'::public.active_status_t)",
                Object.class,administrator().id(),adminAccount.id());
        mvc.perform(change(pev,"APPROVED",adminAccess)).andExpect(status().isUnauthorized());
    }

    @Test void concurrentDecisionsAllowOnlyOneTransition() throws Exception {
        UUID pev=citizenPev();
        var approveAdmin=administrator(); String approveAccess=login(approveAdmin.email());
        var rejectAdmin=administrator(); String rejectAccess=login(rejectAdmin.email());
        var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var approve=pool.submit(() -> { start.await(); return mvc.perform(change(pev,"APPROVED",approveAccess))
                .andReturn().getResponse().getStatus(); });
            var reject=pool.submit(() -> { start.await(); return mvc.perform(change(pev,"REJECTED",rejectAccess))
                .andReturn().getResponse().getStatus(); });
            start.countDown();
            assertThat(List.of(approve.get(30,TimeUnit.SECONDS),reject.get(30,TimeUnit.SECONDS)))
                .containsExactlyInAnyOrder(200,409);
        }
        assertThat(admin().queryForObject("SELECT status::text FROM pev WHERE id=?",String.class,pev))
            .isIn("APPROVED","REJECTED");
        assertThat(auditUpdates(pev,approveAdmin.id())+auditUpdates(pev,rejectAdmin.id()))
            .isEqualTo(2);
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder change(
            UUID pev,String status,String access) {
        return patch("/api/v1/admin/pevs/{id}/status",pev)
            .header("Authorization","Bearer "+access).contentType(MediaType.APPLICATION_JSON)
            .content("{\"status\":\""+status+"\"}");
    }

    private UUID citizenPev() throws Exception { return requestPev(citizen().email()); }

    private UUID establishmentPev() throws Exception {
        UUID type=UUID.randomUUID();
        admin().update("INSERT INTO establishment_type(id,name) VALUES (?,?)",type,"Tipo-"+type);
        String email=email();
        mvc.perform(post("/api/v1/auth/registrations/establishments")
            .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of(
                "name","Estabelecimento PEV","email",email,"password",PASSWORD,
                "cnpj",digits(14),"typeId",type,"address",address()))))
            .andExpect(status().isCreated());
        return requestPev(email);
    }

    private Account citizen() throws Exception {
        String email=email();
        var response=mvc.perform(post("/api/v1/auth/registrations/citizens")
            .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of(
                "name","Cidadão PEV","email",email,"password",PASSWORD,"cpf",digits(11)))))
            .andExpect(status().isCreated()).andReturn();
        return new Account(UUID.fromString(json.readTree(response.getResponse().getContentAsString())
            .get("id").asText()),email);
    }

    private Account administrator() {
        UUID id=UUID.randomUUID(); String email=email();
        var encoder=new Argon2PasswordEncoder(16,32,1,19456,2);
        admin().update("INSERT INTO users(id,name,email,password_hash,user_type) VALUES (?,'Admin',?,?,'ADMIN')",
            id,email,encoder.encode(PASSWORD));
        return new Account(id,email);
    }

    private UUID requestPev(String email) throws Exception {
        var response=mvc.perform(post("/api/v1/pevs").header("Authorization","Bearer "+login(email))
            .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("address",address()))))
            .andExpect(status().isCreated()).andReturn();
        return UUID.fromString(json.readTree(response.getResponse().getContentAsString()).get("id").asText());
    }

    private String login(String email) throws Exception {
        var response=mvc.perform(post("/api/v1/auth/mobile/login")
            .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of(
                "email",email,"password",PASSWORD))))
            .andExpect(status().isOk()).andReturn();
        return json.readTree(response.getResponse().getContentAsString()).get("accessToken").asText();
    }

    private Boolean isPev(UUID owner) {
        return admin().queryForObject("SELECT is_pev FROM establishment WHERE id=?",Boolean.class,owner);
    }
    private long auditUpdates(UUID pev,UUID actor) {
        return admin().queryForObject("SELECT count(*) FROM pev_log WHERE id=? AND operation='UPDATE' AND performed_by=?",
            Long.class,pev,actor);
    }
    private Map<String,String> address() {
        return Map.of("state","SP","city","Cidade","neighborhood","Bairro",
            "street","Rua do PEV","number","10","cep","01001000");
    }
    private JdbcTemplate admin() {
        return new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword()));
    }
    private String email() { return UUID.randomUUID()+"@test.invalid"; }
    private String digits(int length) {
        return new java.math.BigInteger(UUID.randomUUID().toString().replace("-",""),16).toString().substring(0,length);
    }
    private record Account(UUID id,String email) { }
}
