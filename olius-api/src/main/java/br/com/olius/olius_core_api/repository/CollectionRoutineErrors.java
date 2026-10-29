package br.com.olius.olius_core_api.repository;

import br.com.olius.olius_core_api.exception.BusinessRuleException;
import br.com.olius.olius_core_api.exception.ResourceConflictException;
import br.com.olius.olius_core_api.exception.ResourceNotFoundException;
import br.com.olius.olius_core_api.exception.enums.ApiErrorCode;
import java.sql.SQLException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import org.postgresql.util.PSQLException;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

/** Somente rejeições deliberadas dos entrypoints deste domínio; ACL e CHECKs desconhecidos não viram erro do cliente. */
@Component
public class CollectionRoutineErrors {
    public RuntimeException translate(DataAccessException failure) {
        Set<Throwable> seen=Collections.newSetFromMap(new IdentityHashMap<>());
        for(Throwable cause=failure;cause!=null && seen.add(cause);cause=cause.getCause()) {
            if(cause instanceof SQLException sql) {
                RuntimeException result=chain(sql);
                if(result!=null) return result;
            }
        }
        return failure;
    }
    /** Depois de validar motivo, recurso e ator, 22023 desta função representa transição concorrente/incompatível. */
    public RuntimeException translateRejection(DataAccessException failure) {
        RuntimeException result=translate(failure);
        if(result instanceof BusinessRuleException business && business.getCode()==ApiErrorCode.BUSINESS_RULE_VIOLATION)
            return new BusinessRuleException(BusinessRuleException.Reason.STATE_CONFLICT);
        return result;
    }
    private RuntimeException chain(SQLException sql) {
        Set<SQLException> seen=Collections.newSetFromMap(new IdentityHashMap<>());
        for(SQLException item=sql;item!=null && seen.add(item);item=item.getNextException()) {
            if(item instanceof PSQLException pg && pg.getServerErrorMessage()!=null
                    && "exec_stmt_raise".equals(pg.getServerErrorMessage().getRoutine())) {
                RuntimeException result=switch(item.getSQLState()) {
                    case "22000" -> new ResourceConflictException();
                    case "55000", "23514", "42501" -> new BusinessRuleException(BusinessRuleException.Reason.STATE_CONFLICT);
                    case "22023" -> new BusinessRuleException(BusinessRuleException.Reason.INCONSISTENT_DATA);
                    case "P0002" -> new ResourceNotFoundException();
                    default -> null;
                };
                if(result!=null) return result;
            }
        }
        return null;
    }
}
