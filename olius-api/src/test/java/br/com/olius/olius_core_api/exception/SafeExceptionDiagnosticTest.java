package br.com.olius.olius_core_api.exception;

import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class SafeExceptionDiagnosticTest {
    @Test
    void findsNestedSqlStateWithoutRetainingMessage() {
        var result = SafeExceptionDiagnostic.from(
                new IllegalStateException("secret", new SQLException("private SQL", "23514")));
        assertThat(result.rootCauseType()).isEqualTo(SQLException.class.getName());
        assertThat(result.sqlState()).isEqualTo("23514");
        assertThat(result.toString()).doesNotContain("secret", "private SQL");
    }

    @Test
    void followsJdbcNextException() {
        var batch = new SQLException("secret");
        batch.setNextException(new SQLException("private", "08006"));
        assertThat(SafeExceptionDiagnostic.from(batch).sqlState()).isEqualTo("08006");
    }

    @Test
    void rejectsArbitrarySqlStateText() {
        var result = SafeExceptionDiagnostic.from(new SQLException("secret", "secret\nforged-log"));
        assertThat(result.sqlState()).isEqualTo("unavailable");
    }

    @Test
    void terminatesForCyclicCauses() {
        var first = new IllegalStateException("secret");
        var second = new IllegalArgumentException("private", first);
        first.initCause(second);
        assertThat(SafeExceptionDiagnostic.from(first).sqlState()).isEqualTo("unavailable");
    }
}
