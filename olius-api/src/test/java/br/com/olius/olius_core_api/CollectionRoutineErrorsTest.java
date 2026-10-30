package br.com.olius.olius_core_api;

import br.com.olius.olius_core_api.exception.BusinessRuleException;
import br.com.olius.olius_core_api.exception.ResourceConflictException;
import br.com.olius.olius_core_api.exception.ResourceNotFoundException;
import br.com.olius.olius_core_api.repository.CollectionRoutineErrors;
import java.sql.SQLException;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;
import org.springframework.jdbc.UncategorizedSQLException;
import static org.assertj.core.api.Assertions.*;

class CollectionRoutineErrorsTest {
    private final CollectionRoutineErrors errors=new CollectionRoutineErrors();
    static Stream<Arguments> deliberateErrors() {
        return Stream.of(Arguments.of("22000",ResourceConflictException.class),
            Arguments.of("55000",BusinessRuleException.class),Arguments.of("23514",BusinessRuleException.class),
            Arguments.of("42501",BusinessRuleException.class),Arguments.of("22023",BusinessRuleException.class),
            Arguments.of("P0002",ResourceNotFoundException.class));
    }
    @ParameterizedTest @MethodSource("deliberateErrors")
    void mapsOnlyDeliberateRejections(String state,Class<? extends RuntimeException> type) {
        var sql=new PSQLException(new ServerErrorMessage("SERROR\0C"+state+"\0Mprivate diagnostic\0Rexec_stmt_raise\0\0"));
        var failure=new UncategorizedSQLException("operation","private SQL",sql);
        assertThat(errors.translate(failure)).isInstanceOf(type).hasMessageNotContaining("private");
    }
    @Test void aclConstraintConnectionAndUnknownRaiseRemainForGlobalHandler() {
        for(String state:new String[]{"42501","23514","08006","XXXXX"}) {
            var sql=new PSQLException(new ServerErrorMessage("SERROR\0C"+state+"\0Mprivate diagnostic\0Raclcheck_error\0\0"));
            var failure=new UncategorizedSQLException("operation","private SQL",sql);
            assertThat(errors.translate(failure)).isSameAs(failure);
        }
        var sql=new PSQLException(new ServerErrorMessage("SERROR\0CXXXXX\0Mprivate diagnostic\0Rexec_stmt_raise\0\0"));
        var failure=new UncategorizedSQLException("operation","private SQL",sql);
        assertThat(errors.translate(failure)).isSameAs(failure);
    }
    @Test void rejectionStateUsesConflictButActualAclFailureIsNotTranslated() {
        var sql=new PSQLException(new ServerErrorMessage("SERROR\0C22023\0Mprivate diagnostic\0Rexec_stmt_raise\0\0"));
        var failure=new UncategorizedSQLException("operation","private SQL",sql);
        assertThat(((BusinessRuleException)errors.translateRejection(failure)).getCode())
            .isEqualTo(br.com.olius.olius_core_api.exception.enums.ApiErrorCode.BUSINESS_STATE_CONFLICT);
        var acl=new UncategorizedSQLException("operation","private SQL",new SQLException("private diagnostic","42501"));
        assertThat(errors.translateRejection(acl)).isSameAs(acl);
    }
    @Test void traversesNextExceptionsWithoutParsingTheirMessages() {
        var first=new SQLException("not a business rule","XXXXX");
        first.setNextException(new PSQLException(new ServerErrorMessage("SERROR\0CP0002\0Mprivate diagnostic\0Rexec_stmt_raise\0\0")));
        var failure=new UncategorizedSQLException("operation","private SQL",first);
        assertThat(errors.translate(failure)).isInstanceOf(ResourceNotFoundException.class);
    }
}
