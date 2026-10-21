package br.com.olius.olius_core_api.repository;

import br.com.olius.olius_core_api.exception.BusinessRuleException;
import br.com.olius.olius_core_api.exception.ResourceConflictException;
import br.com.olius.olius_core_api.exception.ResourceNotFoundException;
import java.sql.SQLException;
import java.util.Collections;
import java.util.IdentityHashMap;
import org.postgresql.util.PSQLException;
import org.springframework.dao.DataAccessException;

/** Adaptador local: não altera a classificação global de SQLSTATE. */
final class AdminRoutineErrors {
    private AdminRoutineErrors() { }

    static RuntimeException translate(DataAccessException failure) {
        var seen = Collections.newSetFromMap(new IdentityHashMap<Throwable,Boolean>());
        for (Throwable cause = failure; cause != null && seen.add(cause); cause = cause.getCause()) {
            if (cause instanceof SQLException sql) {
                RuntimeException mapped = translateSqlChain(sql);
                if (mapped != null) return mapped;
            }
        }
        // ACL, conexão, controle ausente e estados desconhecidos continuam no handler existente.
        return failure;
    }

    private static RuntimeException translateSqlChain(SQLException sql) {
        var seen = Collections.newSetFromMap(new IdentityHashMap<SQLException,Boolean>());
        for (SQLException item=sql; item!=null && seen.add(item); item=item.getNextException()) {
            RuntimeException mapped = deliberateRejection(item);
            if (mapped != null) return mapped;
        }
        return null;
    }

    private static RuntimeException deliberateRejection(SQLException sql) {
        // Campo estruturado do protocolo PG16; não lê mensagem, SQL ou contexto textual.
        if (!(sql instanceof PSQLException pg) || pg.getServerErrorMessage()==null
                || !"exec_stmt_raise".equals(pg.getServerErrorMessage().getRoutine())) return null;
        return switch (sql.getSQLState()) {
            case "55000", "42501" -> new BusinessRuleException(BusinessRuleException.Reason.STATE_CONFLICT);
            case "23505" -> new ResourceConflictException();
            case "P0002" -> new ResourceNotFoundException();
            default -> null;
        };
    }

}
