package br.com.olius.olius_core_api;

import br.com.olius.olius_core_api.exception.*;
import br.com.olius.olius_core_api.exception.enums.ApiErrorCode;
import br.com.olius.olius_core_api.repository.CollectionOperationErrors;
import br.com.olius.olius_core_api.repository.CollectionOperationErrors.Operation;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.postgresql.util.*;
import org.springframework.jdbc.UncategorizedSQLException;
import static org.assertj.core.api.Assertions.*;

class CollectionOperationErrorsTest {
    private final CollectionOperationErrors errors=new CollectionOperationErrors();
    @ParameterizedTest @ValueSource(strings={"ARRIVAL","ACCEPTANCE","FAILED_VISIT"})
    void stateMeaningDependsOnOperation(String operation) {
        var result=(BusinessRuleException)errors.translate(failure("22023","exec_stmt_raise"),Operation.valueOf(operation));
        assertThat(result.getCode()).isEqualTo(ApiErrorCode.BUSINESS_STATE_CONFLICT);
    }
    @ParameterizedTest @ValueSource(strings={"22023","23514","23503"})
    void recordFactFailuresAreBusinessValidation(String state) {
        assertThat(((BusinessRuleException)errors.translate(failure(state,"exec_stmt_raise"),Operation.RECORD)).getCode()).isEqualTo(ApiErrorCode.BUSINESS_RULE_VIOLATION);
    }
    @ParameterizedTest @ValueSource(strings={"22023","23514","23503"})
    void correctionFactFailuresAreBusinessValidation(String state) {
        var translated=(BusinessRuleException)errors.translate(failure(state,"exec_stmt_raise"),Operation.CORRECTION);
        assertThat(translated.getCode()).isEqualTo(ApiErrorCode.BUSINESS_RULE_VIOLATION);
    }
    @Test void correctionDoesNotHideConcurrencyOrRealPermissionFailures() {
        var conflict=failure("40001","exec_stmt_raise");
        var permission=failure("42501","aclcheck_error");
        assertThat(errors.translate(conflict,Operation.CORRECTION)).isSameAs(conflict);
        assertThat(errors.translate(permission,Operation.CORRECTION)).isSameAs(permission);
    }
    @Test void historyIntegrityFailureIsInternalAndNeverAnEmptyResultOrStateConflict() {
        var failure=failure("55000","exec_stmt_raise");
        assertThat(errors.translate(failure,Operation.HISTORY)).isInstanceOf(IllegalStateException.class)
            .hasMessageNotContaining("private").hasCause(failure.getSQLException());
        var denied=failure("42501","exec_stmt_raise");
        assertThat(errors.translate(denied,Operation.HISTORY)).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        var acl=failure("42501","aclcheck_error");
        assertThat(errors.translate(acl,Operation.HISTORY)).isSameAs(acl);
        var missing=failure("P0002","exec_stmt_raise");
        assertThat(errors.translate(missing,Operation.HISTORY)).isInstanceOf(ResourceNotFoundException.class);
    }
    @Test void doesNotTurnRealPrivilegeOrUnknownFailuresIntoClientErrors() {
        for(String state:new String[]{"42501","23503","23514","08006","XXXXX"}) {
            var failure=failure(state,"aclcheck_error");assertThat(errors.translate(failure,Operation.RECORD)).isSameAs(failure);
        }
        var unknown=failure("XXXXX","exec_stmt_raise");assertThat(errors.translate(unknown,Operation.RECORD)).isSameAs(unknown);
    }
    @Test void translatesOriginalConflictAndMissingResourceWithoutSensitiveMessages() {
        assertThat(errors.translate(failure("22000","exec_stmt_raise"),Operation.RECORD)).isInstanceOf(ResourceConflictException.class).hasMessageNotContaining("private");
        assertThat(errors.translate(failure("P0002","exec_stmt_raise"),Operation.FAILED_VISIT)).isInstanceOf(ResourceNotFoundException.class);
        assertThat(errors.translate(failure("55000","exec_stmt_raise"),Operation.FAILED_VISIT)).isInstanceOf(BusinessRuleException.class);
        assertThat(errors.translate(failure("42501","exec_stmt_raise"),Operation.RECORD)).isInstanceOf(BusinessRuleException.class);
    }
    @Test void followsJdbcNextExceptionChain() {
        var first=new SQLException("batch");first.setNextException(failure("P0002","exec_stmt_raise").getSQLException());
        assertThat(errors.translate(new UncategorizedSQLException("operation","private SQL",first),Operation.RECORD)).isInstanceOf(ResourceNotFoundException.class);
    }
    private UncategorizedSQLException failure(String state,String routine) {
        return new UncategorizedSQLException("operation","private SQL",new PSQLException(new ServerErrorMessage("SERROR\0C"+state+"\0Mprivate diagnostic\0R"+routine+"\0\0")));
    }
}
