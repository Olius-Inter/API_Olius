package br.com.olius.olius_core_api.exception;

import java.sql.SQLException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

import br.com.olius.olius_core_api.exception.enums.ApiErrorCode;
import org.postgresql.util.PSQLException;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Component;

@Component
public class PersistenceExceptionTranslator {
    // Apenas restrições com interpretação pública inequívoca entram aqui.
    // Nome de schema + tabela + constraint evita confundir objetos de auditoria.
    private static final Map<String, ApiErrorCode> KNOWN_UNIQUES = Map.of(
        "users.users_email_key", ApiErrorCode.REGISTRATION_CONFLICT,
        "citizens.citizens_cpf_key", ApiErrorCode.REGISTRATION_CONFLICT,
        "establishment.establishment_cnpj_key", ApiErrorCode.REGISTRATION_CONFLICT,
        "telephone.uq_telephone_user_number", ApiErrorCode.DUPLICATE_TELEPHONE,
        "pev.pev_citizen_id_key", ApiErrorCode.PEV_ALREADY_EXISTS,
        "pev.pev_establishment_id_key", ApiErrorCode.PEV_ALREADY_EXISTS
    );

    public ApiErrorCode translate(Throwable failure) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable current = failure; current != null && seen.add(current); current = current.getCause()) {
            if (current instanceof OptimisticLockingFailureException
                    || current instanceof CannotAcquireLockException) {
                return ApiErrorCode.CONCURRENT_MODIFICATION;
            }
            if (current instanceof SQLException sql) {
                ApiErrorCode result = translateSqlChain(sql);
                if (result != ApiErrorCode.INTERNAL_ERROR) {
                    return result;
                }
            }
        }
        return ApiErrorCode.INTERNAL_ERROR;
    }

    private ApiErrorCode translateSqlChain(SQLException sql) {
        Set<SQLException> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (SQLException current = sql; current != null && seen.add(current); current = current.getNextException()) {
            String state = current.getSQLState();
            if ("40001".equals(state) || "40P01".equals(state) || "55P03".equals(state)) {
                return ApiErrorCode.CONCURRENT_MODIFICATION;
            }
            // Não classificar senha errada (28P01) ou falta de privilégio (42501) como falha do cliente.
            if ("08001".equals(state) || "08006".equals(state) || "57P01".equals(state)
                    || "57P02".equals(state) || "57P03".equals(state) || "53300".equals(state)) {
                return ApiErrorCode.SERVICE_UNAVAILABLE;
            }
            if ("23505".equals(state) && current instanceof PSQLException pg) {
                var error = pg.getServerErrorMessage();
                if (error != null && "public".equals(error.getSchema())) {
                    return KNOWN_UNIQUES.getOrDefault(error.getTable() + "." + error.getConstraint(),
                            ApiErrorCode.INTERNAL_ERROR);
                }
            }
        }
        return ApiErrorCode.INTERNAL_ERROR;
    }
}
