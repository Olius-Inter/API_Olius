package br.com.olius.olius_core_api;

import br.com.olius.olius_core_api.testfixture.OfficialPostgres;
import br.com.olius.olius_core_api.service.AuditContext;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import static org.assertj.core.api.Assertions.*;

/** Escritas reais com a identidade restrita; observações e falhas são exclusivas da fixture. */
@Testcontainers
class AuthAuditContextIntegrationTest {
    @Container static final OfficialPostgres POSTGRES=new OfficialPostgres();
    private static final String[] SETTINGS={"app.audit_actor","app.current_user_id",
            "app.operational_driver_id","app.audit_reason"};
    enum Operation { OPEN, ROTATE, REVOKE, REVOKE_ALL }
    record Session(UUID user, UUID id, byte[] hash) { }

    @BeforeAll
    static void installFaultProbe() throws Exception {
        try (var connection=owner(); var statement=connection.createStatement()) {
            statement.execute("""
                    CREATE FUNCTION test_support.fail_auth_write() RETURNS trigger LANGUAGE plpgsql AS $$
                    BEGIN
                        IF current_setting('olius_test.fail_auth_write',true)='on' THEN
                            RAISE EXCEPTION 'Fixture failure' USING ERRCODE='P7001';
                        END IF;
                        RETURN NEW;
                    END $$;
                    CREATE TRIGGER test_auth_write_failure BEFORE INSERT OR UPDATE ON public.auth_session
                    FOR EACH ROW EXECUTE FUNCTION test_support.fail_auth_write();
                    """);
        }
    }

    @ParameterizedTest @EnumSource(Operation.class)
    void restoresAllSettingsAfterSuccessAndEarlyReturn(Operation operation) throws Exception {
        Session session=session();
        UUID externalActor=user();
        try (var connection=api()) {
            connection.setAutoCommit(false);
            externalContext(connection,externalActor);
            assertThat(call(connection,operation,session,false)).isEqualTo("OK");
            assertContext(connection,externalActor);
            assertThat(call(connection,operation,session,true)).isEqualTo(switch(operation) {
                case OPEN,ROTATE -> "INVALID_INPUT";
                case REVOKE -> "INVALID_SESSION";
                case REVOKE_ALL -> "USER_UNAVAILABLE";
            });
            assertContext(connection,externalActor);
            connection.commit();
        }
        try(var connection=owner()) {
            assertThat(scalar(connection,"""
                    SELECT bool_and(actor_kind='USER' AND performed_by=user_id
                        AND operational_driver_id IS NULL)::text
                    FROM auth_session_log WHERE user_id=?
                    """,session.user())).isEqualTo("true");
        }
    }

    @ParameterizedTest @EnumSource(Operation.class)
    void rollsBackFailedWriteAndPreservesCallerContext(Operation operation) throws Exception {
        Session session=session();
        UUID externalActor=user();
        long sessionsBefore=count("auth_session",session.user());
        long tokensBefore=tokens(session.user());
        try (var connection=api()) {
            connection.setAutoCommit(false);
            externalContext(connection,externalActor);
            setting(connection,"olius_test.fail_auth_write","on");
            var savepoint=connection.setSavepoint();
            assertThatThrownBy(() -> call(connection,operation,session,false))
                    .isInstanceOfSatisfying(SQLException.class,
                        error -> assertThat(error.getSQLState()).isEqualTo("P7001"));
            // Uma falha SQL aborta a transação JDBC até rollback (aqui, ao savepoint do teste).
            connection.rollback(savepoint);
            assertContext(connection,externalActor);
            connection.commit();
        }
        assertThat(count("auth_session",session.user())).isEqualTo(sessionsBefore);
        assertThat(tokens(session.user())).isEqualTo(tokensBefore);
        try (var connection=api()) {
            assertThat(scalar(connection,"SELECT is_valid::text FROM get_auth_session_status(?,?)",
                    session.user(),session.id())).isEqualTo("true");
        }
    }

    @Test
    void reuseRestoresCallerButAuditsSystemAndCommitsRevocation() throws Exception {
        Session session=session();
        UUID actor=user();
        try (var connection=api()) {
            connection.setAutoCommit(false);
            externalContext(connection,actor);
            assertThat(call(connection,Operation.ROTATE,session,false)).isEqualTo("OK");
            assertContext(connection,actor);
            assertThat(call(connection,Operation.ROTATE,session,false)).isEqualTo("REFRESH_REUSED");
            assertContext(connection,actor);
            connection.commit();
        }
        try (var connection=owner()) {
            assertThat(scalar(connection,"""
                    SELECT bool_and(actor_kind='SYSTEM' AND performed_by IS NULL
                        AND operational_driver_id IS NULL)::text FROM auth_session_log
                    WHERE id=? AND audit_reason='Reutilização de refresh token consumido'
                    """,session.id())).isEqualTo("true");
        }
        try (var connection=api()) {
            assertThat(scalar(connection,"SELECT is_valid::text FROM get_auth_session_status(?,?)",
                    session.user(),session.id())).isEqualTo("false");
        }
    }

    @Test
    void springTransactionsDoNotLeakContextWhenPoolReusesPhysicalConnection() throws Exception {
        UUID user=user();
        try (var pool=new HikariDataSource()) {
            pool.setJdbcUrl(POSTGRES.getJdbcUrl()); pool.setUsername(OfficialPostgres.API_USER);
            pool.setPassword(OfficialPostgres.API_PASSWORD); pool.setMaximumPoolSize(1);
            var jdbc=new JdbcTemplate(pool);
            var transaction=new TransactionTemplate(new DataSourceTransactionManager(pool));
            Integer pid=transaction.execute(status -> {
                assertEmptyContext(jdbc);
                jdbc.queryForObject("SELECT result_code FROM open_auth_session(?,?,?)",String.class,user,hash(),"not-used-for-login");
                assertEmptyContext(jdbc);
                new AuditContext(jdbc).user(user,"Operação seguinte");
                jdbc.update("UPDATE users SET name='Updated fixture' WHERE id=?",user);
                return jdbc.queryForObject("SELECT pg_backend_pid()",Integer.class);
            });
            transaction.executeWithoutResult(status -> {
                assertThat(jdbc.queryForObject("SELECT pg_backend_pid()",Integer.class)).isEqualTo(pid);
                assertEmptyContext(jdbc);
            });
        }
        try(var connection=owner()) {
            assertThat(scalar(connection,"""
                    SELECT bool_and(performed_by=? AND actor_kind='USER')::text FROM users_log
                    WHERE id=? AND audit_reason='Operação seguinte'
                    """,user,user)).isEqualTo("true");
        }
    }

    @Test
    void historicalAuditPatchRejectsNewLoginContractAndPreservesPrivileges() throws Exception {
        String update=Files.readString(Path.of("scripts/sql/update-auth-audit-context.sql"));
        try(var connection=owner(); var statement=connection.createStatement()) {
            // Atualização histórica deve recusar o contrato novo sem recriar o login inseguro.
            assertThatThrownBy(() -> statement.execute(update)).isInstanceOf(SQLException.class);
            statement.execute("ROLLBACK");
            assertThat(scalar(connection,"SELECT (to_regprocedure('public.open_auth_session(uuid,bytea)') IS NULL)::text")).isEqualTo("true");
            assertThat(scalar(connection,"""
                    SELECT (proconfig=ARRAY['search_path=pg_catalog, pg_temp']
                        AND proowner='olius_auth_owner'::regrole AND prosecdef
                        AND has_function_privilege('olius_test_api',oid,'EXECUTE'))::text
                    FROM pg_proc WHERE oid='public.open_auth_session(uuid,bytea,text)'::regprocedure
                    """)).isEqualTo("true");
        }
    }

    private static void assertEmptyContext(JdbcTemplate jdbc) {
        for(String name:SETTINGS) assertThat(jdbc.queryForObject(
                "SELECT coalesce(current_setting(?,true),'')",String.class,name)).isEmpty();
    }

    private String call(Connection connection,Operation operation,Session session,boolean invalid) throws SQLException {
        return switch(operation) {
            case OPEN -> scalar(connection,"SELECT result_code FROM open_auth_session(?,?,?)",
                    invalid?null:session.user(),invalid?null:hash(),"not-used-for-login");
            case ROTATE -> scalar(connection,"SELECT result_code FROM rotate_auth_refresh_token(?,?)",
                    invalid?null:session.hash(),invalid?null:hash());
            case REVOKE -> scalar(connection,"SELECT revoke_auth_session(?,?)",
                    invalid?null:session.user(),invalid?null:session.id());
            case REVOKE_ALL -> scalar(connection,"SELECT result_code FROM revoke_user_auth_sessions(?)",
                    invalid?null:session.user());
        };
    }

    private Session session() throws SQLException {
        UUID user=user(); byte[] hash=hash();
        try(var connection=api(); var query=connection.prepareStatement("SELECT session_id FROM open_auth_session(?,?,?)")) {
            query.setObject(1,user); query.setBytes(2,hash); query.setString(3,"not-used-for-login");
            try(var result=query.executeQuery()) {
                assertThat(result.next()).isTrue();
                return new Session(user,result.getObject(1,UUID.class),hash);
            }
        }
    }

    private static UUID user() {
        try(var connection=owner(); var query=connection.prepareStatement("""
                INSERT INTO users(name,email,password_hash,user_type)
                VALUES ('Audit fixture',?,'not-used-for-login','CITIZENS') RETURNING id
                """)) {
            query.setString(1,UUID.randomUUID()+"@test.invalid");
            try(var result=query.executeQuery()) { result.next(); return result.getObject(1,UUID.class); }
        } catch(SQLException error) { throw new IllegalStateException("Fixture user",error); }
    }

    private void externalContext(Connection connection,UUID actor) throws SQLException {
        setting(connection,SETTINGS[0],"USER"); setting(connection,SETTINGS[1],actor.toString());
        // Inexistente propositalmente: a função precisa limpar o motorista antes da auditoria.
        setting(connection,SETTINGS[2],actor.toString()); setting(connection,SETTINGS[3],"Contexto externo");
    }

    private void assertContext(Connection connection,UUID actor) throws SQLException {
        String[] values={"USER",actor.toString(),actor.toString(),"Contexto externo"};
        for(int i=0;i<SETTINGS.length;i++) assertThat(scalar(connection,
                "SELECT current_setting(?,true)",SETTINGS[i])).isEqualTo(values[i]);
    }

    private static void setting(Connection connection,String name,String value) throws SQLException {
        scalar(connection,"SELECT set_config(?,?,true)",name,value);
    }

    private static String scalar(Connection connection,String sql,Object... args) throws SQLException {
        try(var query=connection.prepareStatement(sql)) {
            for(int i=0;i<args.length;i++) query.setObject(i+1,args[i]);
            try(var result=query.executeQuery()) { assertThat(result.next()).isTrue(); return result.getString(1); }
        }
    }

    private long count(String table,UUID user) throws SQLException {
        // table é sempre literal interno do teste, nunca entrada HTTP.
        try(var connection=owner()) { return Long.parseLong(scalar(connection,
                "SELECT count(*) FROM "+table+" WHERE user_id=?",user)); }
    }

    private long tokens(UUID user) throws SQLException {
        try(var connection=owner()) { return Long.parseLong(scalar(connection,"""
                SELECT count(*) FROM auth_refresh_token t JOIN auth_session s ON s.id=t.session_id WHERE s.user_id=?
                """,user)); }
    }

    private static byte[] hash() { byte[] hash=new byte[32]; new java.security.SecureRandom().nextBytes(hash); return hash; }
    private static Connection owner() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword());
    }
    private static Connection api() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(),OfficialPostgres.API_USER,OfficialPostgres.API_PASSWORD);
    }
}
