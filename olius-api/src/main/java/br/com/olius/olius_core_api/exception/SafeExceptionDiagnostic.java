package br.com.olius.olius_core_api.exception;

import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * Metadados exclusivos dos logs internos. Não retém mensagens ou valores SQL.
 * Ciclos em causas e exceções JDBC são ignorados por identidade.
 */
record SafeExceptionDiagnostic(String rootCauseType, String sqlState) {
    static SafeExceptionDiagnostic from(Throwable failure) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        Throwable root = failure;
        while (root.getCause() != null && seen.add(root.getCause())) {
            root = root.getCause();
        }
        return new SafeExceptionDiagnostic(root.getClass().getName(), findSqlState(failure));
    }

    private static String findSqlState(Throwable failure) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        var pending = new ArrayDeque<Throwable>();
        pending.add(failure);
        while (!pending.isEmpty()) {
            Throwable current = pending.removeFirst();
            if (!seen.add(current)) {
                continue;
            }
            if (current instanceof SQLException sql) {
                String state = sql.getSQLState();
                // Não aceitar texto arbitrário ou quebras de linha como SQLSTATE.
                if (state != null && state.matches("[0-9A-Z]{5}")) {
                    return state;
                }
                if (sql.getNextException() != null) {
                    pending.addLast(sql.getNextException());
                }
            }
            if (current.getCause() != null) {
                pending.addLast(current.getCause());
            }
        }
        return "unavailable";
    }
}
