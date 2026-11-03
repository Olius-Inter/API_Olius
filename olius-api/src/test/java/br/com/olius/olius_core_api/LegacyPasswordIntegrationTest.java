package br.com.olius.olius_core_api;

import br.com.olius.olius_core_api.dto.IdentityRequests.Login;
import br.com.olius.olius_core_api.repository.AuthSessionRepository;
import br.com.olius.olius_core_api.repository.CredentialUpgradeRepository;
import br.com.olius.olius_core_api.security.SecretTokens;
import br.com.olius.olius_core_api.service.AuthenticationService;
import br.com.olius.olius_core_api.service.SessionTransactions;
import br.com.olius.olius_core_api.testfixture.LegacyPasswords;
import br.com.olius.olius_core_api.testfixture.OfficialPostgres;
import java.sql.Connection;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@Testcontainers @AutoConfigureMockMvc
@SpringBootTest(properties={"spring.config.import=","olius.security.ephemeral-keys=true",
    "olius.security.auth-requests-per-minute=10000"})
class LegacyPasswordIntegrationTest {
    @Container static final OfficialPostgres POSTGRES=new OfficialPostgres();
    private static final String PASSWORD="  Ação🔑東京  "; // Deliberadamente preserva espaços e não impõe política de cadastro.
    private static final String NEW_PASSWORD="Replacement2!";
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username",() -> OfficialPostgres.API_USER);
        registry.add("spring.datasource.password",() -> OfficialPostgres.API_PASSWORD);
    }
    @Autowired AuthenticationService authentication;
    @Autowired SessionTransactions sessions;
    @Autowired PasswordEncoder passwords;
    @Autowired SecretTokens secrets;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @MockitoSpyBean AuthSessionRepository sessionRepository;
    @MockitoSpyBean CredentialUpgradeRepository credentials;

    @ParameterizedTest @ValueSource(strings={"CITIZENS","ESTABLISHMENT","ADMIN"})
    void loginUpgradesAtomicallyAndNextLoginUsesArgon2(String profile) throws Exception {
        Account account=account(profile,LegacyPasswords.hash(PASSWORD));
        var tokens=authentication.login(new Login(account.email(),PASSWORD));
        String current=stored(account);
        assertThat(current).startsWith("$argon2id$");
        assertThat(passwords.matches(PASSWORD,current)).isTrue();
        assertThat(countSessions(account)).isEqualTo(1);
        mvc.perform(get("/api/v1/auth/session").header("Authorization","Bearer "+tokens.access().accessToken()))
            .andExpect(status().isOk());
        assertThat(authentication.login(new Login(account.email(),PASSWORD)).access()).isNotNull();
        assertThat(stored(account)).isEqualTo(current);
        assertThat(countSessions(account)).isEqualTo(2);
        assertThat(owner().queryForObject("""
            SELECT bool_and(actor_kind='USER' AND performed_by=id)
            FROM users_log WHERE id=? AND operation='UPDATE'
            """,Boolean.class,account.id())).isTrue();
        assertThat(owner().queryForObject("""
            SELECT count(*) FROM information_schema.columns
            WHERE table_name='users_log' AND column_name='password_hash'
            """,Integer.class)).isZero();
    }

    @Test void wrongPasswordUnknownAndMalformedHaveSamePublicErrorWithoutChanges() throws Exception {
        Account account=account("CITIZENS",LegacyPasswords.hash(PASSWORD));
        Account malformed=account("CITIZENS","pbkdf2$999999999$x$y");
        for (String email : new String[]{account.email(),malformed.email(),UUID.randomUUID()+"@example.invalid"}) {
            mvc.perform(post("/api/v1/auth/mobile/login").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(java.util.Map.of("email",email,"password","wrong"))))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
        }
        assertThat(stored(account)).isEqualTo(account.hash());
        assertThat(stored(malformed)).isEqualTo(malformed.hash());
        assertThat(countSessions(account)+countSessions(malformed)).isZero();
    }

    @Test void inactiveLegacyAccountIsNotUpgraded() {
        Account account=account("CITIZENS",LegacyPasswords.hash(PASSWORD));
        owner().update("UPDATE users SET status='INACTIVE' WHERE id=?",account.id());
        assertThatThrownBy(() -> authentication.login(new Login(account.email(),PASSWORD)))
            .isInstanceOf(BadCredentialsException.class);
        assertThat(stored(account)).isEqualTo(account.hash()); assertThat(countSessions(account)).isZero();
    }

    @Test void currentCredentialDoesNotUseUpgradePath() {
        Account account=account("CITIZENS",passwords.encode(PASSWORD));
        authentication.login(new Login(account.email(),PASSWORD));
        assertThat(stored(account)).isEqualTo(account.hash());
        verify(credentials,never()).replace(eq(account.id()),anyString(),anyString());
    }

    @Test void sessionSqlFailureRollsBackUpgradeAndAudit() {
        Account account=account("CITIZENS",LegacyPasswords.hash(PASSWORD));
        doThrow(new DataAccessResourceFailureException("fixture"))
            .when(sessionRepository).open(eq(account.id()),any(byte[].class),anyString());
        assertThatThrownBy(() -> authentication.login(new Login(account.email(),PASSWORD)))
            .isInstanceOf(DataAccessResourceFailureException.class);
        unchanged(account);
    }

    @Test void sessionNonOkAlsoRollsBackUpgradeAndAudit() {
        Account account=account("CITIZENS",LegacyPasswords.hash(PASSWORD));
        doReturn(new AuthSessionRepository.Outcome("CREDENTIAL_CHANGED",null,account.id(),null))
            .when(sessionRepository).open(eq(account.id()),any(byte[].class),anyString());
        assertThatThrownBy(() -> authentication.login(new Login(account.email(),PASSWORD)))
            .isInstanceOf(BadCredentialsException.class);
        unchanged(account);
    }

    @Test void realSqlConstraintFailureRollsBackHashSessionAndAudit() {
        Account existing=account("CITIZENS",passwords.encode(PASSWORD));
        byte[] duplicated=secrets.hash(secrets.generate());
        sessions.open(existing.id(),duplicated,existing.hash());
        Account account=account("CITIZENS",LegacyPasswords.hash(PASSWORD));
        String replacement=passwords.encode(PASSWORD);
        assertThatThrownBy(() -> sessions.openWithCredentialUpgrade(account.id(),duplicated,account.hash(),replacement))
            .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        unchanged(account);
    }

    @Test void realSessionInvalidInputRollsBackUpgrade() {
        Account account=account("CITIZENS",LegacyPasswords.hash(PASSWORD));
        String replacement=passwords.encode(PASSWORD);
        assertThatThrownBy(() -> sessions.openWithCredentialUpgrade(account.id(),new byte[1],account.hash(),replacement))
            .isInstanceOf(BadCredentialsException.class);
        unchanged(account);
    }

    @Test void credentialWriteFailureCannotOpenSession() {
        Account account=account("CITIZENS",LegacyPasswords.hash(PASSWORD));
        doThrow(new DataAccessResourceFailureException("fixture"))
            .when(credentials).replace(eq(account.id()),anyString(),anyString());
        assertThatThrownBy(() -> authentication.login(new Login(account.email(),PASSWORD)))
            .isInstanceOf(DataAccessResourceFailureException.class);
        unchanged(account);
        verify(sessionRepository,never()).open(eq(account.id()),any(byte[].class),anyString());
    }

    @Test void twoConcurrentUpgradesHaveOneWinnerAndNoUnvalidatedRetry() throws Exception {
        Account account=account("CITIZENS",LegacyPasswords.hash(PASSWORD));
        String first=passwords.encode(PASSWORD), second=passwords.encode(PASSWORD);
        var start=new CountDownLatch(1);
        try (var executor=Executors.newFixedThreadPool(2)) {
            Callable<Boolean> a=() -> { start.await(); return attemptUpgrade(account,first); };
            Callable<Boolean> b=() -> { start.await(); return attemptUpgrade(account,second); };
            var one=executor.submit(a); var two=executor.submit(b); start.countDown();
            assertThat(java.util.List.of(one.get(20,java.util.concurrent.TimeUnit.SECONDS),
                two.get(20,java.util.concurrent.TimeUnit.SECONDS))).containsExactlyInAnyOrder(true,false);
        }
        assertThat(stored(account)).isIn(first,second); assertThat(countSessions(account)).isEqualTo(1);
        assertThat(authentication.login(new Login(account.email(),PASSWORD)).access()).isNotNull();
    }

    @Test void resetWinningRowLockPreventsOverwriteAndObsoleteSession() throws Exception {
        Account account=account("CITIZENS",LegacyPasswords.hash(PASSWORD));
        String token=secrets.generate(), next=passwords.encode(NEW_PASSWORD);
        owner().queryForMap("SELECT * FROM issue_password_reset(?,?,interval '30 minutes')",account.email(),secrets.hash(token));
        try (Connection connection=ownerSource().getConnection()) {
            connection.setAutoCommit(false);
            try (var statement=connection.prepareStatement("SELECT complete_password_reset(?,?)")) {
                statement.setBytes(1,secrets.hash(token)); statement.setString(2,next);
                try (var result=statement.executeQuery()) { assertThat(result.next()).isTrue(); assertThat(result.getString(1)).isEqualTo("OK"); }
            }
            assertBlockedUpgradeFailsAfterCommit(connection,account);
        }
        assertThat(stored(account)).isEqualTo(next); assertThat(countSessions(account)).isZero();
        assertThat(authentication.login(new Login(account.email(),NEW_PASSWORD)).access()).isNotNull();
    }

    @Test void inactivationWinningRowLockPreventsMigrationAndSession() throws Exception {
        Account account=account("CITIZENS",LegacyPasswords.hash(PASSWORD));
        try (Connection connection=ownerSource().getConnection()) {
            connection.setAutoCommit(false);
            try (var statement=connection.prepareStatement("UPDATE users SET status='INACTIVE' WHERE id=?")) {
                statement.setObject(1,account.id()); statement.executeUpdate();
            }
            assertBlockedUpgradeFailsAfterCommit(connection,account);
        }
        unchanged(account,false);
    }

    @Test void resetAfterSuccessfulUpgradeRevokesThatSession() {
        Account account=account("CITIZENS",LegacyPasswords.hash(PASSWORD));
        var login=authentication.login(new Login(account.email(),PASSWORD));
        String token=secrets.generate(), next=passwords.encode(NEW_PASSWORD);
        owner().queryForMap("SELECT * FROM issue_password_reset(?,?,interval '30 minutes')",account.email(),secrets.hash(token));
        assertThat(owner().queryForObject("SELECT complete_password_reset(?,?)",String.class,secrets.hash(token),next)).isEqualTo("OK");
        assertThat(stored(account)).isEqualTo(next);
        assertThat(owner().queryForObject("SELECT bool_and(revoked_at IS NOT NULL) FROM auth_session WHERE user_id=?",Boolean.class,account.id())).isTrue();
        assertThat(login.access()).isNotNull();
    }

    private void assertBlockedUpgradeFailsAfterCommit(Connection blocker, Account account) throws Exception {
        int pid;
        try (var statement=blocker.createStatement(); var result=statement.executeQuery("SELECT pg_backend_pid()")) {
            result.next(); pid=result.getInt(1);
        }
        String replacement=passwords.encode(PASSWORD);
        try (var executor=Executors.newSingleThreadExecutor()) {
            var result=executor.submit(() -> attemptUpgrade(account,replacement));
            try {
                await().atMost(Duration.ofSeconds(10)).until(() -> owner().queryForObject(
                    "SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE ? = ANY(pg_blocking_pids(pid)))",Boolean.class,pid));
                blocker.commit();
                assertThat(result.get(10,java.util.concurrent.TimeUnit.SECONDS)).isFalse();
            } finally { blocker.rollback(); }
        }
    }
    private boolean attemptUpgrade(Account account,String replacement) {
        try { sessions.openWithCredentialUpgrade(account.id(),secrets.hash(secrets.generate()),account.hash(),replacement); return true; }
        catch (BadCredentialsException exception) { return false; }
    }
    private void unchanged(Account account) { unchanged(account,true); }
    private void unchanged(Account account,boolean noUpdates) {
        assertThat(stored(account)).isEqualTo(account.hash()); assertThat(countSessions(account)).isZero();
        if (noUpdates) assertThat(owner().queryForObject("SELECT count(*) FROM users_log WHERE id=? AND operation='UPDATE'",Integer.class,account.id())).isZero();
    }
    private String stored(Account account) { return owner().queryForObject("SELECT password_hash FROM users WHERE id=?",String.class,account.id()); }
    private int countSessions(Account account) { return owner().queryForObject("SELECT count(*) FROM auth_session WHERE user_id=?",Integer.class,account.id()); }
    private Account account(String profile,String hash) {
        String email="legacy-"+UUID.randomUUID()+"@example.invalid";
        UUID id=owner().queryForObject("INSERT INTO users(name,email,password_hash,user_type) VALUES ('Legacy fixture',?,?,?::user_type_t) RETURNING id",UUID.class,email,hash,profile);
        return new Account(id,email,hash);
    }
    private static DriverManagerDataSource ownerSource() { return new DriverManagerDataSource(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword()); }
    private static JdbcTemplate owner() { return new JdbcTemplate(ownerSource()); }
    private record Account(UUID id,String email,String hash) {
        @Override public String toString() { return "Account[redacted]"; }
    }
}
