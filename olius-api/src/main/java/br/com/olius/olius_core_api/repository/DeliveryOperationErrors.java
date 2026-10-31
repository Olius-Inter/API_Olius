package br.com.olius.olius_core_api.repository;

import br.com.olius.olius_core_api.exception.BusinessRuleException;
import br.com.olius.olius_core_api.exception.ResourceConflictException;
import br.com.olius.olius_core_api.exception.ResourceNotFoundException;
import java.sql.SQLException;
import java.util.Collections;
import java.util.IdentityHashMap;
import org.postgresql.util.PSQLException;
import org.springframework.dao.DataAccessException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

/** SQLSTATE de negócio só é interpretado no contexto da procedure de entrega. */
@Component
public class DeliveryOperationErrors {
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
                            case "22000" -> new ResourceConflictException();
                            case "42501" -> new AccessDeniedException("Entrega não autorizada");
                            case "55000" -> new BusinessRuleException(BusinessRuleException.Reason.STATE_CONFLICT);
                            case "P0002" -> new ResourceNotFoundException();
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
