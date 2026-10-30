package br.com.olius.olius_core_api;

import br.com.olius.olius_core_api.testfixture.OfficialPostgres;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class OfficialReferenceDataIntegrationTest {
    @Container static final OfficialPostgres POSTGRES=new OfficialPostgres();

    @Test
    void officialReferencesAreRepeatableAndValidationDoesNotWriteAudit() throws Exception {
        try(var connection=DriverManager.getConnection(POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(),POSTGRES.getPassword());var statement=connection.createStatement()) {
            statement.execute(resource("initial_data/01_reference_data.sql"));
            long firstAudit=auditRows(connection);
            statement.execute(resource("initial_data/01_reference_data.sql"));
            assertThat(auditRows(connection)).isEqualTo(firstAudit);
            statement.execute(resource("initial_data/02_validate.sql"));
            assertThat(auditRows(connection)).isEqualTo(firstAudit);
            try(var rows=statement.executeQuery("""
                    SELECT (SELECT count(*) FROM subscription_plan),
                           (SELECT count(*) FROM certificate_level),
                           (SELECT count(*) FROM establishment_type),
                           (SELECT count(*) FROM collection_failure_reason)
                    """)) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getLong(1)).isEqualTo(3);
                assertThat(rows.getLong(2)).isEqualTo(3);
                assertThat(rows.getLong(3)).isEqualTo(7);
                assertThat(rows.getLong(4)).isEqualTo(5);
            }
        }
    }

    private long auditRows(Connection connection) throws Exception {
        try(var statement=connection.createStatement();var rows=statement.executeQuery("""
                SELECT (SELECT count(*) FROM subscription_plan_log)
                     + (SELECT count(*) FROM certificate_level_log)
                     + (SELECT count(*) FROM collection_failure_reason_log)
                """)) { rows.next();return rows.getLong(1); }
    }

    private String resource(String name) throws Exception {
        return new ClassPathResource("db/olius/"+name).getContentAsString(StandardCharsets.UTF_8);
    }
}
