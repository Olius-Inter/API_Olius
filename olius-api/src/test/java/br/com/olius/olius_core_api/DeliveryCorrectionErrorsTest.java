package br.com.olius.olius_core_api;

import br.com.olius.olius_core_api.exception.*;
import br.com.olius.olius_core_api.repository.DeliveryCorrectionErrors;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.postgresql.util.*;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.security.access.AccessDeniedException;
import static org.assertj.core.api.Assertions.*;

class DeliveryCorrectionErrorsTest {
    private final DeliveryCorrectionErrors errors=new DeliveryCorrectionErrors();

    @Test void mapsOnlyKnownProcedureErrorsWithoutExposingDiagnostics() {
        assertThat(errors.translate(failure("22023","exec_stmt_raise")))
            .isInstanceOf(BusinessRuleException.class).hasMessageNotContaining("private");
        assertThat(errors.translate(failure("42501","exec_stmt_raise"))).isInstanceOf(AccessDeniedException.class);
        assertThat(errors.translate(failure("P0002","exec_stmt_raise"))).isInstanceOf(ResourceNotFoundException.class);
        var conflict=failure("40001","exec_stmt_raise");
        assertThat(errors.translate(conflict)).isInstanceOf(OptimisticLockingFailureException.class)
            .hasCause(conflict.getSQLException());
    }

    @Test void preservesTechnicalPermissionsUnknownAndNonPostgresFailures() {
        for(var failure:new UncategorizedSQLException[]{failure("42501","aclcheck_error"),
                failure("XXXXX","exec_stmt_raise"),
                new UncategorizedSQLException("operation",null,new SQLException("technical","22023")),
                new UncategorizedSQLException("operation",null,new PSQLException("technical",PSQLState.UNKNOWN_STATE))})
            assertThat(errors.translate(failure)).isSameAs(failure);
    }

    @Test void followsNextExceptionAndStopsOnCyclicChains() {
        var sql=new SQLException("batch");
        sql.setNextException(failure("P0002","exec_stmt_raise").getSQLException());
        assertThat(errors.translate(new UncategorizedSQLException("operation",null,sql)))
            .isInstanceOf(ResourceNotFoundException.class);
        var cyclic=new SQLException("cycle");cyclic.setNextException(cyclic);
        var failure=new UncategorizedSQLException("operation",null,cyclic);
        assertThat(errors.translate(failure)).isSameAs(failure);
    }

    private UncategorizedSQLException failure(String state,String routine) {
        return new UncategorizedSQLException("operation","private SQL",new PSQLException(
            new ServerErrorMessage("SERROR\0C"+state+"\0Mprivate diagnostic\0R"+routine+"\0\0")));
    }
}
