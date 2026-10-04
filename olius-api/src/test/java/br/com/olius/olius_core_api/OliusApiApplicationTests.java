package br.com.olius.olius_core_api;

import br.com.olius.olius_core_api.exception.*;
import br.com.olius.olius_core_api.exception.enums.ApiErrorCode;
import br.com.olius.olius_core_api.testfixture.FlushProbe;
import jakarta.persistence.EntityManager;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import static org.assertj.core.api.Assertions.*;

@EntityScan(basePackageClasses = FlushProbe.class)
@Testcontainers
@SpringBootTest(properties = {"spring.config.import=", "spring.jpa.open-in-view=false"})
class OliusApiApplicationTests {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16.15");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username", POSTGRES::getUsername);
        properties.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @BeforeAll
    static void installOfficialSchema() throws Exception {
        try (var connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(), POSTGRES.getPassword()); var statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA test_support");
            statement.execute("CREATE TABLE test_support.flush_probe (id uuid PRIMARY KEY, probe_value text UNIQUE)");
            for (String script : new String[]{"01_structure.sql", "02_check_constraints.sql", "03_indexes.sql", "05_triggers.sql"}) {
                String sql = new ClassPathResource("db/olius/" + script).getContentAsString(StandardCharsets.UTF_8);
                // O driver PostgreSQL processa blocos dollar-quoted; não dividir o script por ponto e vírgula.
                statement.execute(sql);
            }
        }
    }

    @Autowired EntityManager entityManager;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired PersistenceExceptionTranslator translator;

    @Test
    void contextLoadsAgainstOfficialSchema() {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM information_schema.tables WHERE table_schema='public' AND table_type='BASE TABLE'", Integer.class))
                .isEqualTo(52);
        assertThat(jdbc.queryForObject("SELECT current_database()", String.class)).isNotEqualTo("olius_dev");
    }

    @Test
    void realUniqueViolationBecomesRegistrationConflict() {
        String email = UUID.randomUUID() + "@test.invalid";
        insertUser(email);
        Throwable failure = catchThrowable(() -> insertUser(email));
        assertThat(failure).isNotNull();
        assertThat(translator.translate(failure)).isEqualTo(ApiErrorCode.REGISTRATION_CONFLICT);
    }

    @Test
    void sameTelephoneIsAllowedForDifferentUsersButNotSameUser() {
        UUID first = insertUser(UUID.randomUUID() + "@test.invalid");
        UUID second = insertUser(UUID.randomUUID() + "@test.invalid");
        jdbc.update("INSERT INTO telephone(telephone,user_id) VALUES (?,?)", "11999999999", first);
        jdbc.update("INSERT INTO telephone(telephone,user_id) VALUES (?,?)", "11999999999", second);
        Throwable failure = catchThrowable(() -> jdbc.update("INSERT INTO telephone(telephone,user_id) VALUES (?,?)", "11999999999", first));
        assertThat(translator.translate(failure)).isEqualTo(ApiErrorCode.DUPLICATE_TELEPHONE);
    }

    @Test
    void auditFailureRollsBackBusinessWriteAndSnapshots() {
        String email = UUID.randomUUID() + "@test.invalid";
        Long before = jdbc.queryForObject("SELECT count(*) FROM users_log", Long.class);
        Throwable failure = catchThrowable(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            jdbc.execute("SELECT set_config('app.audit_actor','USER',true)");
            jdbc.execute("SELECT set_config('app.current_user_id','',true)");
            insertUser(email);
        }));
        assertThat(failure).isNotNull();
        assertThat(translator.translate(failure)).isEqualTo(ApiErrorCode.INTERNAL_ERROR);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE email=?", Integer.class, email)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users_log", Long.class)).isEqualTo(before);
    }

    @Test
    void invalidAuditContextIsInternalAndDoesNotExposeDatabaseMessage() {
        Throwable failure = catchThrowable(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            jdbc.execute("SELECT set_config('app.audit_actor','INVALID',true)");
            insertUser(UUID.randomUUID() + "@test.invalid");
        }));
        assertThat(translator.translate(failure)).isEqualTo(ApiErrorCode.INTERNAL_ERROR);
        assertThat(findSql(failure).getSQLState()).isEqualTo("P0001");
    }

    @Test
    void unknownCheckIsInternal() {
        Throwable failure = catchThrowable(() -> jdbc.update(
                "INSERT INTO users(name,email,password_hash,user_type) VALUES ('',?,'test-hash','ADMIN')",
                UUID.randomUUID() + "@test.invalid"));
        assertThat(findSql(failure).getSQLState()).isEqualTo("23514");
        assertThat(translator.translate(failure)).isEqualTo(ApiErrorCode.INTERNAL_ERROR);
    }

    @Test
    void failureAtCommitIsStillTranslatable() throws Exception {
        // Fixture exclusiva de teste: as constraints oficiais não são alteradas.
        try (var connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            try (var statement = connection.createStatement()) {
                statement.execute("CREATE TEMP TABLE commit_probe (id integer UNIQUE DEFERRABLE INITIALLY DEFERRED)");
                connection.setAutoCommit(false);
                statement.execute("INSERT INTO commit_probe VALUES (1),(1)");
                Throwable failure = catchThrowable(connection::commit);
                assertThat(findSql(failure).getSQLState()).isEqualTo("23505");
                assertThat(translator.translate(failure)).isEqualTo(ApiErrorCode.INTERNAL_ERROR);
                connection.rollback();
            }
        }
    }

    @Test
    void jpaFlushFailureRollsBackBothPendingWrites() {
        String value = UUID.randomUUID().toString();
        Throwable failure = catchThrowable(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            entityManager.persist(new FlushProbe(UUID.randomUUID(), value));
            entityManager.persist(new FlushProbe(UUID.randomUUID(), value));
            entityManager.flush();
        }));
        assertThat(findSql(failure).getSQLState()).isEqualTo("23505");
        assertThat(translator.translate(failure)).isEqualTo(ApiErrorCode.INTERNAL_ERROR);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM test_support.flush_probe WHERE probe_value=?", Integer.class, value)).isZero();
    }

    @Test
    void serverCalculatedPointsViolationIsInternal() {
        Throwable failure = catchThrowable(() -> jdbc.update(
                "INSERT INTO delivery_pev(oil_volume_liters,points_earned,delivery_date,citizen_id,pev_id,validated_by) VALUES (3.7,99,CURRENT_TIMESTAMP,?,?,?)",
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()));
        assertThat(findSql(failure).getSQLState()).isEqualTo("23514");
        assertThat(translator.translate(failure)).isEqualTo(ApiErrorCode.INTERNAL_ERROR);
    }

    private UUID insertUser(String email) {
        return jdbc.queryForObject(
                "INSERT INTO users(name,email,password_hash,user_type) VALUES ('Teste',?,'test-hash','ADMIN') RETURNING id",
                UUID.class, email);
    }

    private SQLException findSql(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof SQLException sql) return sql;
        }
        throw new AssertionError("SQLSTATE esperado", failure);
    }
}
