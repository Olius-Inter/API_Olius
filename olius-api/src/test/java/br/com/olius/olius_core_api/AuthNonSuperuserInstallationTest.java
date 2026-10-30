package br.com.olius.olius_core_api;

import br.com.olius.olius_core_api.testfixture.OfficialPostgres;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import static org.assertj.core.api.Assertions.*;

/** A conta de instalação deste teste não é a conta operacional da Core. */
@Testcontainers
class AuthNonSuperuserInstallationTest {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(OfficialPostgres.IMAGE);

    @Test
    void installsMainScriptsWithoutSuperuserOrParameterGrants() throws Exception {
        try (var connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var statement = connection.createStatement()) {
            statement.execute("CREATE ROLE install_probe NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOREPLICATION NOBYPASSRLS");
            // O nome vem exclusivamente do container descartável controlado pelo teste.
            statement.execute("ALTER DATABASE \"" + POSTGRES.getDatabaseName().replace("\"", "\"\"") + "\" OWNER TO install_probe");
            statement.execute("SET SESSION AUTHORIZATION install_probe");
            try (var result = statement.executeQuery("""
                    SELECT rolsuper, rolcreaterole, EXISTS (
                        SELECT 1 FROM unnest(ARRAY['app.audit_actor','app.current_user_id',
                        'app.operational_driver_id','app.audit_reason']) name
                        WHERE has_parameter_privilege(current_user,name,'SET'))
                    FROM pg_roles WHERE rolname=current_user
                    """)) {
                assertThat(result.next()).isTrue();
                for (int column=1; column<=3; column++) assertThat(result.getBoolean(column)).isFalse();
            }
            // Comprova que o teste realmente reproduz a restrição do instalador.
            assertThatThrownBy(() -> statement.execute("""
                    CREATE FUNCTION public.old_parameter_probe() RETURNS void
                    LANGUAGE plpgsql SET app.audit_actor='SYSTEM' AS $$ BEGIN RETURN; END $$
                    """)).isInstanceOfSatisfying(SQLException.class,
                        error -> assertThat(error.getSQLState()).isEqualTo("42501"));
            for (String script : new String[]{"01_structure.sql", "02_check_constraints.sql",
                    "03_indexes.sql", "04_functions_procedures.sql", "05_triggers.sql"}) {
                statement.execute(new ClassPathResource("db/olius/"+script)
                        .getContentAsString(StandardCharsets.UTF_8));
            }
            try (var result = statement.executeQuery("""
                    SELECT count(*), bool_and(prosecdef AND proconfig=ARRAY['search_path=pg_catalog, pg_temp'])
                    FROM pg_proc WHERE pronamespace='public'::regnamespace AND proname IN
                    ('open_auth_session','rotate_auth_refresh_token','revoke_auth_session','revoke_user_auth_sessions')
                    """)) {
                assertThat(result.next()).isTrue();
                assertThat(result.getInt(1)).isEqualTo(4);
                assertThat(result.getBoolean(2)).isTrue();
            }
            connection.setAutoCommit(false);
            statement.execute("SELECT set_config('app.audit_actor','EXTERNAL',true)");
            for (String call : new String[]{"SELECT * FROM open_auth_session(NULL,NULL)",
                    "SELECT * FROM rotate_auth_refresh_token(NULL,NULL)",
                    "SELECT revoke_auth_session(NULL,NULL)", "SELECT * FROM revoke_user_auth_sessions(NULL)"}) {
                statement.execute(call);
                try (var result=statement.executeQuery("SELECT current_setting('app.audit_actor')")) {
                    assertThat(result.next()).isTrue();
                    assertThat(result.getString(1)).isEqualTo("EXTERNAL");
                }
            }
            connection.rollback();
        }
    }
}
