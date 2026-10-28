package br.com.olius.olius_core_api;

import br.com.olius.olius_core_api.testfixture.OfficialPostgres;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import static org.assertj.core.api.Assertions.*;

/** Contrato JDBC e privilégios reais; não implementa autenticação HTTP. */
@Testcontainers
class AuthDatabaseContractTest {
    @Container
    static final OfficialPostgres POSTGRES = new OfficialPostgres();

    @ParameterizedTest
    @CsvSource({"ADMIN,168", "CITIZENS,360", "ESTABLISHMENT,360"})
    void sessionDurationComesFromDatabase(String profile, long hours) throws Exception {
        try (var connection = api()) {
            UUID user = user(connection, profile);
            UUID session = open(connection, user, hash());
            assertThat(valid(connection, user, session)).isTrue();
            assertThat(valid(connection, UUID.randomUUID(), session)).isFalse();
            // Observação administrativa exclusiva do teste, não disponível à Core.
            try (var admin = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                 var query = admin.prepareStatement("SELECT extract(epoch from idle_expires_at-created_at)/3600 FROM auth_session WHERE id=?")) {
                query.setObject(1, session);
                try (var result = query.executeQuery()) {
                    assertThat(result.next()).isTrue();
                    assertThat(result.getLong(1)).isEqualTo(hours);
                }
            }
        }
    }

    @Test
    void refreshReuseRevocationSurvivesCommitAndAnotherConnection() throws Exception {
        UUID user;
        UUID session;
        byte[] original = hash();
        byte[] successor = hash();
        try (var connection = api()) {
            user = user(connection, "ADMIN");
            session = open(connection, user, original);
            connection.setAutoCommit(false);
            assertThat(rotate(connection, original, successor)).isEqualTo("OK");
            connection.commit();
            assertThat(rotate(connection, original, hash())).isEqualTo("REFRESH_REUSED");
            // Resultado negativo com efeito de segurança: confirmar antes de responder 401.
            connection.commit();
        }
        try (var connection = api()) {
            assertThat(valid(connection, user, session)).isFalse();
            assertThat(rotate(connection, successor, hash())).isEqualTo("SESSION_REVOKED");
        }
    }

    @Test
    void logoutAndInactivationInvalidateSessions() throws Exception {
        try (var connection = api()) {
            UUID user = user(connection, "ADMIN");
            UUID first = open(connection, user, hash());
            UUID second = open(connection, user, hash());
            try (var query = connection.prepareStatement("SELECT revoke_auth_session(?,?)")) {
                query.setObject(1, user);
                query.setObject(2, first);
                try (var result = query.executeQuery()) {
                    result.next();
                    assertThat(result.getString(1)).isEqualTo("OK");
                }
            }
            assertThat(valid(connection, user, first)).isFalse();
            assertThat(valid(connection, user, second)).isTrue();
            UUID actor = user(connection, "ADMIN");
            try (var query = connection.prepareStatement("SELECT public.set_admin_status(?,?,'INACTIVE')")) {
                query.setObject(1, actor);
                query.setObject(2, user);
                try (var result = query.executeQuery()) { assertThat(result.next()).isTrue(); }
            }
            assertThat(valid(connection, user, second)).isFalse();
        }
    }

    @Test
    void applicationCannotReadHashesOrWriteSessionsDirectly() throws Exception {
        try (var connection = api(); var statement = connection.createStatement()) {
            try (var result = statement.executeQuery("SELECT current_user, rolsuper, rolcreaterole FROM pg_roles WHERE rolname=current_user")) {
                result.next();
                assertThat(result.getString(1)).isEqualTo(OfficialPostgres.API_USER);
                assertThat(result.getBoolean(2)).isFalse();
                assertThat(result.getBoolean(3)).isFalse();
            }
            for (String sql : new String[]{"SELECT token_hash FROM auth_refresh_token",
                    "UPDATE auth_session SET revoked_at=NULL", "DELETE FROM auth_refresh_token"}) {
                assertThatThrownBy(() -> statement.execute(sql)).isInstanceOfSatisfying(SQLException.class,
                        failure -> assertThat(failure.getSQLState()).isEqualTo("42501"));
            }
        }
    }

    private Connection api() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), OfficialPostgres.API_USER, OfficialPostgres.API_PASSWORD);
    }

    private UUID user(Connection connection, String profile) throws SQLException {
        // Conta administrativa já existente é preparada somente pelo proprietário da fixture.
        // A conexão operacional continua proibida de INSERT ADMIN (coberto na suíte de convites).
        if ("ADMIN".equals(profile)) {
            try (var owner = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
                return insertUser(owner, profile);
            }
        }
        return insertUser(connection, profile);
    }

    private UUID insertUser(Connection connection, String profile) throws SQLException {
        // Fixture de usuário: não substitui o cadastro completo da especialização.
        try (var query = connection.prepareStatement("INSERT INTO users(name,email,password_hash,user_type) VALUES ('Teste',?,'fixture-only',?::user_type_t) RETURNING id")) {
            query.setString(1, UUID.randomUUID() + "@test.invalid");
            query.setString(2, profile);
            try (var result = query.executeQuery()) {
                result.next();
                return result.getObject(1, UUID.class);
            }
        }
    }

    private UUID open(Connection connection, UUID user, byte[] hash) throws SQLException {
        try (var query = connection.prepareStatement("SELECT * FROM open_auth_session(?,?)")) {
            query.setObject(1, user);
            query.setBytes(2, hash);
            try (var result = query.executeQuery()) {
                assertThat(result.next()).isTrue();
                assertThat(result.getString("result_code")).isEqualTo("OK");
                return result.getObject("session_id", UUID.class);
            }
        }
    }

    private String rotate(Connection connection, byte[] current, byte[] next) throws SQLException {
        try (var query = connection.prepareStatement("SELECT result_code FROM rotate_auth_refresh_token(?,?)")) {
            query.setBytes(1, current);
            query.setBytes(2, next);
            try (var result = query.executeQuery()) {
                result.next();
                return result.getString(1);
            }
        }
    }

    private boolean valid(Connection connection, UUID user, UUID session) throws SQLException {
        try (var query = connection.prepareStatement("SELECT is_valid FROM get_auth_session_status(?,?)")) {
            query.setObject(1, user);
            query.setObject(2, session);
            try (var result = query.executeQuery()) {
                result.next();
                return result.getBoolean(1);
            }
        }
    }

    private byte[] hash() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return bytes;
    }
}
