package br.com.olius.olius_core_api;

import br.com.olius.olius_core_api.dto.AdminInvitationRequests;
import br.com.olius.olius_core_api.security.SecretTokens;
import br.com.olius.olius_core_api.service.AdminInvitationDelivery;
import br.com.olius.olius_core_api.testfixture.OfficialPostgres;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.*;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.*;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@Testcontainers
@AutoConfigureMockMvc
@SpringBootTest(properties={"spring.config.import=","olius.security.ephemeral-keys=true",
    "olius.security.auth-requests-per-minute=10000","springdoc.api-docs.enabled=true"})
@Import(AdminInvitationsIntegrationTest.DeliveryConfiguration.class)
class AdminInvitationsIntegrationTest {
    @Container static final OfficialPostgres POSTGRES=new OfficialPostgres();
    private static final String ROOT="/api/v1/admin/invitations";
    private static final String ACCEPT="/api/v1/auth/admin-invitations/acceptance";
    private static final String PASSWORD="Password1!";

    @DynamicPropertySource static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url",POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username",() -> OfficialPostgres.API_USER);
        properties.add("spring.datasource.password",() -> OfficialPostgres.API_PASSWORD);
    }

    @TestConfiguration(proxyBeanMethods=false)
    static class DeliveryConfiguration {
        @Bean TestDelivery invitationDelivery(JdbcTemplate jdbc,PlatformTransactionManager manager) {
            return new TestDelivery(jdbc,manager);
        }
    }

    /** Test-only: único local onde os segredos enviados são mantidos em memória. */
    static class TestDelivery implements AdminInvitationDelivery {
        private final JdbcTemplate jdbc;
        private final PlatformTransactionManager manager;
        final Map<String,Message> messages=new ConcurrentHashMap<>();
        final AtomicBoolean fail=new AtomicBoolean();
        TestDelivery(JdbcTemplate jdbc,PlatformTransactionManager manager) { this.jdbc=jdbc; this.manager=manager; }
        @Override public void send(Message message) {
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            var check=new TransactionTemplate(manager);
            check.setReadOnly(true);
            Boolean committed=check.execute(status -> jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM public.admin_invitation WHERE email=? AND revoked_at IS NULL)",
                Boolean.class,message.email()));
            assertThat(committed).isTrue();
            if(fail.get()) throw new IllegalStateException("simulated delivery failure");
            messages.put(message.email(),message);
        }
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate limited;
    @Autowired SecretTokens secrets;
    @Autowired PasswordEncoder passwords;
    @Autowired TestDelivery delivery;

    @BeforeEach void resetDelivery() { delivery.fail.set(false); }

    @Test void lifecycleCreatesOnlyOnAcceptanceAndLoginUsesExistingSessions() throws Exception {
        var actor=admin(); String email=email();
        var issued=issue(actor.access(),email.toUpperCase(Locale.ROOT),201);
        UUID id=UUID.fromString(json.readTree(issued.getResponse().getContentAsString()).get("id").asText());
        assertThat(owner().queryForObject("SELECT count(*) FROM users WHERE email=?",Integer.class,email)).isZero();
        var message=delivery.messages.get(email);
        assertThat(message).isNotNull();
        assertThat(issued.getResponse().getContentAsString()).doesNotContain(message.token(),"tokenHash","token_hash");
        assertThat(issued.getResponse().getHeader("Location")).isEqualTo(ROOT+"/"+id);
        var hash=owner().queryForObject("SELECT token_hash FROM admin_invitation WHERE id=?",byte[].class,id);
        assertThat(hash).containsExactly(secrets.hash(message.token()));
        assertThat(owner().queryForObject("SELECT expires_at-issued_at=interval '24 hours' FROM admin_invitation WHERE id=?",Boolean.class,id)).isTrue();
        accept(message.token(),204);
        UUID user=owner().queryForObject("SELECT accepted_user_id FROM admin_invitation WHERE id=?",UUID.class,id);
        assertThat(user).isNotNull();
        String stored=owner().queryForObject("SELECT password_hash FROM users WHERE id=?",String.class,user);
        assertThat(passwords.matches(PASSWORD,stored)).isTrue();
        assertThat(owner().queryForObject("SELECT count(*) FROM auth_session WHERE user_id=?",Integer.class,user)).isZero();
        assertThat(login(email)).isNotBlank();
        accept(message.token(),409);
        mvc.perform(post(ROOT+"/"+id+"/revocation").header("Authorization","Bearer "+actor.access()))
            .andExpect(status().isConflict());
        mvc.perform(get(ROOT+"/"+id).header("Authorization","Bearer "+actor.access()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("ACCEPTED"));
        assertThat(owner().queryForObject("SELECT count(*) FROM admin_invitation_log WHERE id=?",Integer.class,id)).isGreaterThanOrEqualTo(3);
        assertThat(owner().queryForObject("SELECT count(*) FROM information_schema.columns WHERE table_name='admin_invitation_log' AND column_name='token_hash'",Integer.class)).isZero();
    }

    @Test void resendRotatesSecretAndAnyActiveAdminCanRevoke() throws Exception {
        var first=admin(); var other=admin(); String email=email();
        issue(first.access(),email,201); String old=delivery.messages.get(email).token();
        var result=issue(first.access(),email,201); String next=delivery.messages.get(email).token();
        assertThat(next).isNotEqualTo(old); accept(old,409);
        UUID id=UUID.fromString(json.readTree(result.getResponse().getContentAsString()).get("id").asText());
        for(int i=0;i<2;i++) mvc.perform(post(ROOT+"/"+id+"/revocation").header("Authorization","Bearer "+other.access()))
            .andExpect(status().isNoContent());
        accept(next,409);
        mvc.perform(get(ROOT).param("state","REVOKED").header("Authorization","Bearer "+first.access()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.items").isArray());
    }

    @Test void expirationAndInactiveIssuerPreventAcceptanceWithoutLeakingCause() throws Exception {
        var actor=admin(); var other=admin(); String expired=email();
        issue(actor.access(),expired,201);
        owner().update("UPDATE admin_invitation SET issued_at=CURRENT_TIMESTAMP-interval '25 hours',expires_at=CURRENT_TIMESTAMP-interval '1 hour' WHERE email=?",expired);
        accept(delivery.messages.get(expired).token(),409);
        String inactive=email(); issue(actor.access(),inactive,201);
        mvc.perform(patch("/api/v1/admin/users/"+actor.id()+"/status").header("Authorization","Bearer "+other.access())
            .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"INACTIVE\"}"))
            .andExpect(status().isNoContent());
        accept(delivery.messages.get(inactive).token(),409);
        mvc.perform(get(ROOT).header("Authorization","Bearer "+actor.access())).andExpect(status().isUnauthorized());
    }

    @Test void occupiedEmailAndForgedFieldsNeverPromoteExistingAccounts() throws Exception {
        var actor=admin(); String occupied=email();
        owner().update("INSERT INTO users(name,email,password_hash,user_type) VALUES ('Citizen',?,'test','CITIZENS')",occupied.toUpperCase(Locale.ROOT));
        issue(actor.access(),occupied,409);
        String pending=email(); issue(actor.access(),pending,201);
        owner().update("INSERT INTO users(name,email,password_hash,user_type) VALUES ('Citizen',?,'test','CITIZENS')",pending);
        accept(delivery.messages.get(pending).token(),409);
        mvc.perform(post(ROOT).header("Authorization","Bearer "+actor.access()).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("name","Admin","email",email(),"adminId",actor.id()))))
            .andExpect(status().isBadRequest());
        mvc.perform(post(ACCEPT).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("token",secrets.generate(),"password",PASSWORD,"email",email()))))
            .andExpect(status().isBadRequest());
    }

    @Test void invalidInputsAndMissingOrWrongIdentityAreRejected() throws Exception {
        issue(null,email(),401);
        UUID citizen=UUID.randomUUID(); String email=email();
        owner().update("INSERT INTO users(id,name,email,password_hash,user_type) VALUES (?,'Citizen',?,?,'CITIZENS')",citizen,email,passwords.encode(PASSWORD));
        String token=login(email);
        issue(token,email(),403);
        mvc.perform(get(ROOT).header("Authorization","Bearer "+token)).andExpect(status().isForbidden());
        var actor=admin();
        mvc.perform(get(ROOT).param("size","101").header("Authorization","Bearer "+actor.access())).andExpect(status().isBadRequest());
        mvc.perform(get(ROOT).param("page","-1").header("Authorization","Bearer "+actor.access())).andExpect(status().isBadRequest());
        mvc.perform(get(ROOT+"/"+UUID.randomUUID()).header("Authorization","Bearer "+actor.access())).andExpect(status().isNotFound());
        mvc.perform(post(ACCEPT).contentType(MediaType.APPLICATION_JSON).content("{\"token\":\"bad\",\"password\":\"123\"}"))
            .andExpect(status().isBadRequest());
        accept(secrets.generate(),409);
    }

    @Test void deliveryFailurePreservesCommittedInvitationAndExplicitResendRecovers() throws Exception {
        var actor=admin(); String email=email(); delivery.fail.set(true);
        var failed=issue(actor.access(),email,503);
        assertThat(failed.getResponse().getContentAsString()).contains("ADMIN_INVITATION_DELIVERY_FAILED");
        UUID old=owner().queryForObject("SELECT id FROM admin_invitation WHERE email=?",UUID.class,email);
        assertThat(old).isNotNull();
        delivery.fail.set(false); issue(actor.access(),email,201);
        assertThat(owner().queryForObject("SELECT revoked_at IS NOT NULL FROM admin_invitation WHERE id=?",Boolean.class,old)).isTrue();
        accept(delivery.messages.get(email).token(),204);
    }

    @Test void limitedRoleCannotReadHashesWriteAdminOrCallBootstrapAndHelper() {
        List<String> denied=List.of("SELECT token_hash FROM public.admin_invitation",
            "SELECT * FROM public.admin_guard_state",
            "INSERT INTO users(name,email,password_hash,user_type) VALUES ('Bypass','bypass@test.invalid','x','ADMIN')",
            "SELECT public.issue_bootstrap_admin_invitation('x','x@test.invalid',sha256('x'::bytea))",
            "SELECT public.create_admin_invitation_internal(NULL,'x','x@test.invalid',sha256('x'::bytea))");
        for(String sql:denied) assertThatThrownBy(() -> limited.execute(sql)).isInstanceOf(org.springframework.dao.DataAccessException.class);
    }

    @Test void concurrentAcceptanceCreatesOneUser() throws Exception {
        var actor=admin(); String email=email(); issue(actor.access(),email,201);
        String token=delivery.messages.get(email).token();
        var ready=new CountDownLatch(2); var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            List<Future<Integer>> results=new ArrayList<>();
            for(int i=0;i<2;i++) results.add(pool.submit(() -> {
                ready.countDown(); if(!start.await(10,TimeUnit.SECONDS)) throw new AssertionError("start timeout");
                return accept(token,0).getResponse().getStatus();
            }));
            assertThat(ready.await(10,TimeUnit.SECONDS)).isTrue(); start.countDown();
            assertThat(List.of(results.get(0).get(25,TimeUnit.SECONDS),results.get(1).get(25,TimeUnit.SECONDS)))
                .containsExactlyInAnyOrder(204,409);
        } finally { start.countDown(); }
        assertThat(owner().queryForObject("SELECT count(*) FROM users WHERE email=?",Integer.class,email)).isEqualTo(1);
    }

    @Test void technicalGuardFailureIsInternalAndNeverReportedAsInvalidInvitation() throws Exception {
        // Uma única conexão proprietária mantém o servidor isolado desta classe consistente.
        owner().update("DELETE FROM admin_guard_state");
        try {
            var response=accept(secrets.generate(),500);
            assertThat(response.getResponse().getContentAsString()).contains("INTERNAL_ERROR").doesNotContain("55000","Controle administrativo");
        } finally { owner().update("INSERT INTO admin_guard_state(singleton) VALUES(TRUE)"); }
    }

    @Test void realMissingExecutePrivilegeRemainsInternalInsteadOfBusinessConflict() throws Exception {
        owner().execute("REVOKE EXECUTE ON FUNCTION public.accept_admin_invitation(bytea,text) FROM olius_api");
        try {
            var result=accept(secrets.generate(),500);
            assertThat(result.getResponse().getContentAsString()).contains("INTERNAL_ERROR");
        } finally {
            owner().execute("GRANT EXECUTE ON FUNCTION public.accept_admin_invitation(bytea,text) TO olius_api");
        }
    }

    @Test void metadataQueriesDoNotReadHashAndPublicAcceptIsDocumentedWithoutBearer() throws Exception {
        var actor=admin(); issue(actor.access(),email(),201);
        mvc.perform(get(ROOT).header("Authorization","Bearer "+actor.access())).andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("token_hash"))));
        var result=mvc.perform(get("/v3/api-docs")).andExpect(status().isOk()).andReturn();
        var operation=json.readTree(result.getResponse().getContentAsString()).get("paths").get(ACCEPT).get("post");
        assertThat(operation.get("security")==null || operation.get("security").isEmpty()).isTrue();
        assertThat(new AdminInvitationRequests.Accept("secret","password").toString()).doesNotContain("secret","password");
    }

    private MvcResult issue(String access,String email,int expected) throws Exception {
        var request=post(ROOT).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("name","Convidado","email",email)));
        if(access!=null) request.header("Authorization","Bearer "+access);
        return mvc.perform(request).andExpect(status().is(expected)).andReturn();
    }

    private MvcResult accept(String token,int expected) throws Exception {
        var result=mvc.perform(post(ACCEPT).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("token",token,"password",PASSWORD))));
        if(expected!=0) result.andExpect(status().is(expected));
        return result.andReturn();
    }

    private Account admin() throws Exception {
        UUID id=UUID.randomUUID(); String email=email();
        // Fixture proprietária representa conta já existente; HTTP nunca recebe esse privilégio.
        owner().update("INSERT INTO users(id,name,email,password_hash,user_type) VALUES (?,'Admin',?,?,'ADMIN')",id,email,passwords.encode(PASSWORD));
        return new Account(id,email,login(email));
    }

    private String login(String email) throws Exception {
        var result=mvc.perform(post("/api/v1/auth/mobile/login").contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("email",email,"password",PASSWORD))))
            .andExpect(status().isOk()).andReturn();
        return json.readTree(result.getResponse().getContentAsString()).get("accessToken").asText();
    }

    private JdbcTemplate owner() {
        return new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword()));
    }
    private String email() { return UUID.randomUUID()+"@test.invalid"; }
    private record Account(UUID id,String email,String access) { }
}
