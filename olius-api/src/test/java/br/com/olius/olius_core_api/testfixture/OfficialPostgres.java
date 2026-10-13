package br.com.olius.olius_core_api.testfixture;

import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import org.springframework.core.io.ClassPathResource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Instala a referência oficial antes de disponibilizar a conexão ao Spring. */
public final class OfficialPostgres extends PostgreSQLContainer {
    public static final String API_USER = "olius_test_api";
    public static final String API_PASSWORD = "disposable-test-only";

    public OfficialPostgres() {
        super("postgres:16.15");
    }

    @Override
    public void start() {
        super.start();
        try (var connection = DriverManager.getConnection(getJdbcUrl(), getUsername(), getPassword());
             var statement = connection.createStatement()) {
            for (String script : new String[]{
                    "01_structure.sql", "02_check_constraints.sql", "03_indexes.sql",
                    "04_functions_procedures_window_functions.sql", "05_triggers.sql",
                    "data_catalog/01_structure.sql", "data_catalog/02_check_constraints.sql",
                    "data_catalog/03_view.sql", "data_catalog/04_load.sql", "access_control/01_roles.sql"}) {
                // JDBC preserva os blocos dollar-quoted e as transações dos scripts oficiais.
                statement.execute(new ClassPathResource("db/olius/" + script)
                        .getContentAsString(StandardCharsets.UTF_8));
            }
            // Login exclusivo do container: não provisionar contas pessoais nos testes.
            statement.execute("CREATE ROLE " + API_USER + " LOGIN PASSWORD '" + API_PASSWORD
                    + "' NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS");
            statement.execute("GRANT olius_api TO " + API_USER);
            statement.execute("CREATE SCHEMA test_support");
            statement.execute("CREATE TABLE test_support.flush_probe (id uuid PRIMARY KEY, probe_value text UNIQUE)");
        } catch (Exception exception) {
            stop();
            throw new IllegalStateException("Não foi possível instalar o schema oficial de testes", exception);
        }
    }
}
