package br.com.olius.olius_core_api;

import br.com.olius.olius_core_api.security.SecretTokens;
import br.com.olius.olius_core_api.service.PasswordResetDelivery;
import br.com.olius.olius_core_api.testfixture.OfficialPostgres;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mail.MailSendException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@Testcontainers @AutoConfigureMockMvc
@SpringBootTest(properties={"spring.config.import=","olius.security.ephemeral-keys=true",
    "olius.security.auth-requests-per-minute=10000","springdoc.api-docs.enabled=true",
    "olius.password-reset.enabled=true","olius.password-reset.url=https://front.example.invalid/redefinir-senha",
    "olius.password-reset.from=olius@example.invalid","spring.mail.username=olius@example.invalid",
    "spring.mail.password=fixture-only"})
@Import(PasswordResetIntegrationTest.DeliveryConfiguration.class)
class PasswordResetIntegrationTest {
    @Container static final OfficialPostgres POSTGRES=new OfficialPostgres();
    private static final String REQUEST="/api/v1/auth/password-reset-requests";
    private static final String COMPLETE="/api/v1/auth/password-resets";
    private static final String OLD="Password1!";
    private static final String NEXT="NewPassword2!";
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username",() -> OfficialPostgres.API_USER);
        registry.add("spring.datasource.password",() -> OfficialPostgres.API_PASSWORD);
    }
    @TestConfiguration(proxyBeanMethods=false)
    static class DeliveryConfiguration {
        @Bean @Primary TestDelivery testResetDelivery() { return new TestDelivery(); }
    }
    static class TestDelivery implements PasswordResetDelivery {
        final Map<String,Message> messages=new ConcurrentHashMap<>();
        final AtomicBoolean fail=new AtomicBoolean();
        @Override public void send(Message message) {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            var owner=owner();
            var count=owner.queryForObject("SELECT count(*) FROM password_reset_token WHERE token_hash=?",Integer.class,
                new SecretTokens().hash(message.token()));
            assertThat(count).isEqualTo(1); // Outra conexão já enxerga a emissão: commit anterior ao SMTP.
            if(fail.get()) throw new MailSendException("simulated SMTP failure");
            messages.put(message.email(),message);
        }
    }
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired PasswordEncoder passwords;
    @Autowired SecretTokens secrets;
    @Autowired br.com.olius.olius_core_api.service.SessionTransactions sessions;
    @Autowired TestDelivery delivery;
    @Autowired JdbcTemplate limited;
    @Autowired @Qualifier("passwordResetExecutor") ThreadPoolTaskExecutor executor;

    @ParameterizedTest @ValueSource(strings={"CITIZENS","ESTABLISHMENT","ADMIN"})
    void recoveryRevokesEverySessionWithoutAutomaticLogin(String profile) throws Exception {
        var account=account(profile); var first=login(account.email(),OLD); var second=login(account.email(),OLD);
        request(account.email(),202);
        var message=message(account.email());
        var issued=owner().queryForMap("SELECT token_hash,expires_at-issued_at AS ttl FROM password_reset_token WHERE user_id=?",account.id());
        assertThat((byte[])issued.get("token_hash")).containsExactly(secrets.hash(message.token()));
        assertThat(owner().queryForObject("SELECT expires_at-issued_at=interval '30 minutes' FROM password_reset_token WHERE user_id=?",Boolean.class,account.id())).isTrue();
        assertThat(message.expiresAt()).isAfter(Instant.now());
        complete(message.token(),NEXT,204);
        assertPasswordAndSessions(account);
        mvc.perform(get("/api/v1/auth/session").header("Authorization","Bearer "+first.access())).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/auth/session").header("Authorization","Bearer "+second.access())).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/auth/mobile/refresh").contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("refreshToken",first.refresh())))).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/auth/mobile/login").contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("email",account.email(),"password",OLD)))).andExpect(status().isUnauthorized());
        assertThat(login(account.email(),NEXT).access()).isNotBlank();
        complete(message.token(),NEXT,400).andExpect(jsonPath("$.code").value("PASSWORD_RESET_INVALID"));
        assertThat(owner().queryForObject("SELECT count(*) FROM information_schema.columns WHERE table_name IN ('password_reset_token_log','users_log') AND column_name IN ('token_hash','password_hash')",Integer.class)).isZero();
    }

    private void assertPasswordAndSessions(Account account) {
        String stored=owner().queryForObject("SELECT password_hash FROM users WHERE id=?",String.class,account.id());
        assertThat(stored).startsWith("$argon2id$"); assertThat(passwords.matches(NEXT,stored)).isTrue();
        assertThat(owner().queryForObject("SELECT count(*) FROM auth_session WHERE user_id=?",Integer.class,account.id())).isEqualTo(2);
        assertThat(owner().queryForObject("SELECT bool_and(revoked_at IS NOT NULL AND revocation_reason='PASSWORD_RESET') FROM auth_session WHERE user_id=?",Boolean.class,account.id())).isTrue();
        assertThat(owner().queryForObject("SELECT bool_and(actor_kind='SYSTEM' AND performed_by IS NULL) FROM password_reset_token_log WHERE user_id=?",Boolean.class,account.id())).isTrue();
    }

    @Test void unknownInactiveAndAccountQuotaHaveSamePublicResponse() throws Exception {
        var active=account("CITIZENS"); var inactive=account("CITIZENS");
        owner().update("UPDATE users SET status='INACTIVE' WHERE id=?",inactive.id());
        String accepted=request(active.email(),202).getResponse().getContentAsString();
        message(active.email());
        assertThat(request(email(),202).getResponse().getContentAsString()).isEqualTo(accepted);
        assertThat(request(inactive.email(),202).getResponse().getContentAsString()).isEqualTo(accepted);
        assertThat(request(active.email(),202).getResponse().getContentAsString()).isEqualTo(accepted);
        drain();
        assertThat(owner().queryForObject("SELECT count(*) FROM password_reset_token WHERE user_id=?",Integer.class,active.id())).isEqualTo(1);
        assertThat(owner().queryForObject("SELECT count(*) FROM password_reset_token WHERE user_id=?",Integer.class,inactive.id())).isZero();
        assertThat(accepted).doesNotContain(active.email(),"token","userId","resetId");
    }

    @Test void validatedOldCredentialCannotOpenSessionAfterRealPasswordReset() throws Exception {
        var account=account("CITIZENS");
        String validatedHash=limited.queryForObject("SELECT password_hash FROM users WHERE id=?",String.class,account.id());
        assertThat(passwords.matches(OLD,validatedHash)).isTrue();
        String token=secrets.generate(); seed(account.id(),token,false);
        complete(token,NEXT,204);
        String currentHash=limited.queryForObject("SELECT password_hash FROM users WHERE id=?",String.class,account.id());
        assertThat(currentHash).isNotEqualTo(validatedHash);
        assertThat(passwords.matches(NEXT,currentHash)).isTrue();
        // Bean Spring real, transação nova e função PostgreSQL: nenhum retorno é simulado.
        var outcome=sessions.open(account.id(),secrets.hash(secrets.generate()),validatedHash);
        assertThat(outcome.code()).isEqualTo("CREDENTIAL_CHANGED");
        assertThat(outcome.sessionId()).isNull(); assertThat(outcome.expiresAt()).isNull();
        assertThat(owner().queryForObject("SELECT count(*) FROM auth_session WHERE user_id=?",Integer.class,account.id())).isZero();
        assertThat(owner().queryForObject("SELECT count(*) FROM auth_refresh_token r JOIN auth_session s ON s.id=r.session_id WHERE s.user_id=?",Integer.class,account.id())).isZero();
        assertThat(login(account.email(),NEXT).access()).isNotBlank();
    }

    @Test void accountQuotaBlocksFourthIssuanceInsideFifteenMinutes() throws Exception {
        var account=account("CITIZENS");
        for(long minutes:new long[]{2,7,14}) seedIssuedAgo(account.id(),Duration.ofMinutes(minutes));
        var response=request(account.email(),202); drain();
        assertThat(response.getResponse().getContentAsString()).doesNotContain(account.email(),"RATE_LIMITED","userId","resetId");
        assertThat(owner().queryForObject("SELECT count(*) FROM password_reset_token WHERE user_id=?",Integer.class,account.id())).isEqualTo(3);
        assertThat(delivery.messages).doesNotContainKey(account.email());
    }

    @Test void issuancesOlderThanFifteenMinutesDoNotConsumeAccountQuota() throws Exception {
        var account=account("CITIZENS");
        for(long minutes:new long[]{16,17,18}) seedIssuedAgo(account.id(),Duration.ofMinutes(minutes));
        request(account.email(),202); var message=message(account.email());
        assertThat(message.email()).isEqualTo(account.email());
        assertThat(owner().queryForObject("SELECT count(*) FROM password_reset_token WHERE user_id=?",Integer.class,account.id())).isEqualTo(4);
        assertThat(owner().queryForObject("SELECT count(*) FROM password_reset_token WHERE user_id=? AND issued_at>clock_timestamp()-interval '15 minutes'",Integer.class,account.id())).isEqualTo(1);
        assertThat(owner().queryForObject("SELECT bool_and(invalidated_at IS NULL AND consumed_at IS NULL) FROM password_reset_token WHERE user_id=?",Boolean.class,account.id())).isTrue();
    }

    @Test void normalizesOnlyDomainAndPreservesLocalCase() throws Exception {
        String local="Case-"+UUID.randomUUID();
        String email=local+"@example.invalid";
        UUID id=insert(email,"CITIZENS");
        request(local.toLowerCase(java.util.Locale.ROOT)+"@example.invalid",202); drain();
        assertThat(owner().queryForObject("SELECT count(*) FROM password_reset_token WHERE user_id=?",Integer.class,id)).isZero();
        request("  "+local+"@EXAMPLE.INVALID  ",202); message(email);
        assertThat(owner().queryForObject("SELECT count(*) FROM password_reset_token WHERE user_id=?",Integer.class,id)).isEqualTo(1);
    }

    @Test void smtpFailureLeavesCommittedTokenAndGenericAcceptedResponse() throws Exception {
        var account=account("CITIZENS"); delivery.fail.set(true);
        try {
            request(account.email(),202); drain();
            assertThat(delivery.messages).doesNotContainKey(account.email());
            assertThat(owner().queryForObject("SELECT count(*) FROM password_reset_token WHERE user_id=? AND consumed_at IS NULL",Integer.class,account.id())).isEqualTo(1);
            assertThat(passwords.matches(OLD,owner().queryForObject("SELECT password_hash FROM users WHERE id=?",String.class,account.id()))).isTrue();
        } finally { delivery.fail.set(false); }
    }

    @Test void invalidExpiredAndInactiveTokensAreRejectedWithOnePublicCode() throws Exception {
        var expired=account("CITIZENS"); var inactive=account("CITIZENS");
        String expiredToken=secrets.generate(); String inactiveToken=secrets.generate();
        seed(expired.id(),expiredToken,true); seed(inactive.id(),inactiveToken,false);
        owner().update("UPDATE users SET status='INACTIVE' WHERE id=?",inactive.id());
        for(String token:new String[]{"malformed",secrets.generate(),expiredToken,inactiveToken})
            complete(token,NEXT,400).andExpect(jsonPath("$.code").value("PASSWORD_RESET_INVALID"));
    }

    @Test void successInvalidatesEveryOtherPendingLink() throws Exception {
        var account=account("CITIZENS"); String first=secrets.generate(); String other=secrets.generate();
        seed(account.id(),first,false); seed(account.id(),other,false);
        complete(first,NEXT,204); complete(other,NEXT,400);
        assertThat(owner().queryForObject("SELECT invalidated_at IS NOT NULL FROM password_reset_token WHERE token_hash=?",Boolean.class,secrets.hash(other))).isTrue();
    }

    @Test void twoSpringTransactionsCannotConsumeDifferentTokensTwice() throws Exception {
        var account=account("CITIZENS"); String first=secrets.generate(); String second=secrets.generate();
        seed(account.id(),first,false); seed(account.id(),second,false);
        var gate=new java.util.concurrent.CountDownLatch(1);
        try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var a=pool.submit(() -> concurrentComplete(first,gate));
            var b=pool.submit(() -> concurrentComplete(second,gate));
            gate.countDown();
            assertThat(java.util.List.of(a.get(20,java.util.concurrent.TimeUnit.SECONDS),b.get(20,java.util.concurrent.TimeUnit.SECONDS)))
                .containsExactlyInAnyOrder(204,400);
        }
        assertThat(owner().queryForObject("SELECT count(*) FROM password_reset_token WHERE user_id=? AND consumed_at IS NOT NULL",Integer.class,account.id())).isEqualTo(1);
    }
    private int concurrentComplete(String token,java.util.concurrent.CountDownLatch gate) throws Exception {
        if(!gate.await(10,java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("Test barrier timed out");
        return mvc.perform(post(COMPLETE).with(req -> { req.setRemoteAddr(UUID.randomUUID().toString()); return req; })
            .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("token",token,"password",NEXT))))
            .andReturn().getResponse().getStatus();
    }

    @Test void databaseFailureRollsBackPasswordTokenAndSession() throws Exception {
        var account=account("CITIZENS"); var session=login(account.email(),OLD);
        String token=secrets.generate(); seed(account.id(),token,false);
        owner().execute("CREATE FUNCTION test_support.fail_reset_session() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'test failure' USING ERRCODE='P7001'; END $$");
        owner().execute("CREATE TRIGGER test_reset_session BEFORE UPDATE ON auth_session FOR EACH ROW EXECUTE FUNCTION test_support.fail_reset_session()");
        try {
            complete(token,NEXT,500);
            assertThat(passwords.matches(OLD,owner().queryForObject("SELECT password_hash FROM users WHERE id=?",String.class,account.id()))).isTrue();
            assertThat(owner().queryForObject("SELECT consumed_at IS NULL FROM password_reset_token WHERE token_hash=?",Boolean.class,secrets.hash(token))).isTrue();
            mvc.perform(get("/api/v1/auth/session").header("Authorization","Bearer "+session.access())).andExpect(status().isOk());
        } finally { owner().execute("DROP TRIGGER test_reset_session ON auth_session"); owner().execute("DROP FUNCTION test_support.fail_reset_session()"); }
    }

    @Test void inputValidationAndUnknownFieldsNeverMutatePassword() throws Exception {
        request("invalid",400);
        complete(secrets.generate(),"1234",400).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        mvc.perform(post(COMPLETE).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("token",secrets.generate(),"password",NEXT,"userId",UUID.randomUUID()))))
            .andExpect(status().isBadRequest());
    }

    @Test void restrictedConnectionCannotReadOrModifyRecoveryTables() {
        assertThatThrownBy(() -> limited.queryForList("SELECT * FROM password_reset_token")).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(() -> limited.update("DELETE FROM password_reset_token")).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(() -> limited.queryForList("SELECT * FROM password_reset_token_log")).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(owner().queryForObject("SELECT to_regprocedure('public.open_auth_session(uuid,bytea)') IS NULL",Boolean.class)).isTrue();
    }

    @Test void openApiDocumentsPublicResetWithoutBearerOrCookie() throws Exception {
        var document=json.readTree(mvc.perform(get("/v3/api-docs")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(document.path("paths").path(REQUEST).path("post").path("security").size()).isZero();
        assertThat(document.path("paths").path(COMPLETE).path("post").path("responses").has("204")).isTrue();
        assertThat(document.path("paths").path(REQUEST).path("post").path("responses").has("202")).isTrue();
        assertThat(document.path("paths").path(COMPLETE).path("post").path("description").asText()).contains("revoga");
    }

    private MvcResult request(String email,int expected) throws Exception {
        return mvc.perform(post(REQUEST).with(req -> { req.setRemoteAddr(UUID.randomUUID().toString()); return req; })
            .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("email",email))))
            .andExpect(status().is(expected)).andReturn();
    }
    private org.springframework.test.web.servlet.ResultActions complete(String token,String password,int expected) throws Exception {
        return mvc.perform(post(COMPLETE).with(req -> { req.setRemoteAddr(UUID.randomUUID().toString()); return req; })
            .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("token",token,"password",password))))
            .andExpect(status().is(expected));
    }
    private PasswordResetDelivery.Message message(String email) {
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(delivery.messages).containsKey(email));
        drain(); return delivery.messages.get(email);
    }
    private void drain() { await().atMost(Duration.ofSeconds(15)).until(() -> executor.getActiveCount()==0 && executor.getQueueSize()==0); }
    private Account account(String profile) { String email=email(); return new Account(insert(email,profile),email); }
    private UUID insert(String email,String profile) {
        return owner().queryForObject("INSERT INTO users(name,email,password_hash,user_type) VALUES ('Recovery test',?,?,?::user_type_t) RETURNING id",UUID.class,email,passwords.encode(OLD),profile);
    }
    private Tokens login(String email,String password) throws Exception {
        var body=json.readTree(mvc.perform(post("/api/v1/auth/mobile/login").contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("email",email,"password",password))))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        return new Tokens(body.path("accessToken").asText(),body.path("refreshToken").asText());
    }
    private void seedIssuedAgo(UUID user,Duration age) {
        owner().update("INSERT INTO password_reset_token(user_id,token_hash,issued_at,expires_at) VALUES (?,?,clock_timestamp()-?::interval,clock_timestamp()+interval '30 minutes')",
            user,secrets.hash(secrets.generate()),age.toString());
    }
    private void seed(UUID user,String token,boolean expired) {
        owner().update("INSERT INTO password_reset_token(user_id,token_hash,issued_at,expires_at) VALUES (?,?,clock_timestamp()-interval '40 minutes',clock_timestamp()+?::interval)",user,secrets.hash(token),expired?"-10 minutes":"30 minutes");
    }
    private static String email() { return "reset-"+UUID.randomUUID()+"@example.invalid"; }
    private static JdbcTemplate owner() {
        return new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword()));
    }
    private record Account(UUID id,String email) { }
    private record Tokens(String access,String refresh) { }
}
