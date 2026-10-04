package br.com.olius.olius_core_api.exception;

import java.sql.SQLException;

import br.com.olius.olius_core_api.exception.enums.ApiErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataIntegrityViolationException;
import static org.assertj.core.api.Assertions.assertThat;

class PersistenceExceptionTranslatorTest {
    private final PersistenceExceptionTranslator translator = new PersistenceExceptionTranslator();

    @ParameterizedTest
    @ValueSource(strings = {"23505", "23503", "23514", "23502", "23P01", "P0001", "P0002", "42501", "28P01", "22P02"})
    void unknownOrInternalDatabaseFailuresAreNotBlamedOnClient(String state) {
        var error = new DataIntegrityViolationException("private", new SQLException("private", state));
        assertThat(translator.translate(error)).isEqualTo(ApiErrorCode.INTERNAL_ERROR);
    }

    @ParameterizedTest
    @ValueSource(strings = {"40001", "40P01", "55P03"})
    void detectsConcurrentModification(String state) {
        assertThat(translator.translate(new SQLException("private", state)))
                .isEqualTo(ApiErrorCode.CONCURRENT_MODIFICATION);
    }

    @ParameterizedTest
    @ValueSource(strings = {"08001", "08006", "57P01", "57P02", "57P03", "53300"})
    void detectsKnownUnavailableStates(String state) {
        assertThat(translator.translate(new SQLException("private", state)))
                .isEqualTo(ApiErrorCode.SERVICE_UNAVAILABLE);
    }

    @Test
    void followsJdbcBatchExceptionChain() {
        var wrapper = new SQLException("batch");
        wrapper.setNextException(new SQLException("private", "40001"));
        assertThat(translator.translate(wrapper)).isEqualTo(ApiErrorCode.CONCURRENT_MODIFICATION);
    }

    @Test
    void followsCommitFailureCause() {
        var error = new org.springframework.transaction.TransactionSystemException(
                "commit failed", new SQLException("private", "40001"));
        assertThat(translator.translate(error)).isEqualTo(ApiErrorCode.CONCURRENT_MODIFICATION);
    }
}
