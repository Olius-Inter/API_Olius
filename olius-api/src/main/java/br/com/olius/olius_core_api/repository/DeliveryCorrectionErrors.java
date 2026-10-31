package br.com.olius.olius_core_api.repository;

import br.com.olius.olius_core_api.exception.BusinessRuleException;
import br.com.olius.olius_core_api.exception.ResourceNotFoundException;
import java.sql.SQLException;
import java.util.Collections;
import java.util.IdentityHashMap;
import org.postgresql.util.PSQLException;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

/** Interpreta SQLSTATE de negócio apenas na chamada de correct_pev_delivery. */
@Component
public class DeliveryCorrectionErrors {
    public RuntimeException translate(DataAccessException failure) {
        var seen=Collections.newSetFromMap(new IdentityHashMap<Throwable,Boolean>());
        for(Throwable cause=failure;cause!=null && seen.add(cause);cause=cause.getCause()) {
            if(cause instanceof SQLException sql) {
                var chain=Collections.newSetFromMap(new IdentityHashMap<SQLException,Boolean>());
                for(SQLException item=sql;item!=null && chain.add(item);item=item.getNextException()) {
                    if(item instanceof PSQLException pg && pg.getServerErrorMessage()!=null
                            && "exec_stmt_raise".equals(pg.getServerErrorMessage().getRoutine())) {
                        RuntimeException translated=switch(item.getSQLState()) {
                            case "22023" -> new BusinessRuleException(BusinessRuleException.Reason.INCONSISTENT_DATA);
                            case "42501" -> new AccessDeniedException("Correção não autorizada");
                            case "P0002" -> new ResourceNotFoundException();
                            case "40001" -> new OptimisticLockingFailureException("Revisão desatualizada",item);
                            default -> null;
                        };
                        if(translated!=null) return translated;
                    }
                }
            }
        }
        return failure;
    }
}
