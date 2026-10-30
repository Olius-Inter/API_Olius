package br.com.olius.olius_core_api.repository;

import br.com.olius.olius_core_api.exception.*;
import java.sql.SQLException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import org.postgresql.util.PSQLException;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

/** SQLSTATE só ganha significado de negócio no entrypoint correspondente. ACL real continua interna. */
@Component
public class CollectionOperationErrors {
    public enum Operation { ARRIVAL, ACCEPTANCE, RECORD, FAILED_VISIT }
    public RuntimeException translate(DataAccessException failure,Operation operation) {
        Set<Throwable> seen=Collections.newSetFromMap(new IdentityHashMap<>());
        for(Throwable cause=failure;cause!=null && seen.add(cause);cause=cause.getCause()) {
            if(cause instanceof SQLException sql) {
                var translated=chain(sql,operation);
                if(translated!=null) return translated;
            }
        }
        return failure;
    }
    private RuntimeException chain(SQLException sql,Operation operation) {
        Set<SQLException> seen=Collections.newSetFromMap(new IdentityHashMap<>());
        for(SQLException item=sql;item!=null && seen.add(item);item=item.getNextException()) {
            if(item instanceof PSQLException pg && pg.getServerErrorMessage()!=null
                    && "exec_stmt_raise".equals(pg.getServerErrorMessage().getRoutine())) {
                var translated=business(item.getSQLState(),operation);
                if(translated!=null) return translated;
            }
        }
        return null;
    }
    private RuntimeException business(String state,Operation operation) {
        return switch(state) {
            case "P0002" -> new ResourceNotFoundException();
            case "22000" -> new ResourceConflictException();
            case "55000", "42501" -> new BusinessRuleException(BusinessRuleException.Reason.STATE_CONFLICT);
            case "22023" -> new BusinessRuleException(operation==Operation.RECORD
                    ? BusinessRuleException.Reason.INCONSISTENT_DATA : BusinessRuleException.Reason.STATE_CONFLICT);
            case "23514", "23503" -> new BusinessRuleException(BusinessRuleException.Reason.INCONSISTENT_DATA);
            default -> null;
        };
    }
}
