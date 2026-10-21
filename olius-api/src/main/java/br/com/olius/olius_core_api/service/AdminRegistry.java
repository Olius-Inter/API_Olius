package br.com.olius.olius_core_api.service;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Usa exclusivamente a coordenação oficial; nunca conta ADMINs no Java. */
@Component
public class AdminRegistry {
    private final JdbcTemplate jdbc;

    public AdminRegistry(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public void lock() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Coordenação administrativa exige transação");
        }
        // Falha técnica 55000 (linha ausente) não entra no tradutor de rejeições de negócio.
        // A função já é concedida à Core. A trava é reentrante na mesma transação.
        jdbc.queryForObject("SELECT public.lock_admin_registry()", Object.class);
    }

    public void requireActive(UUID actor) {
        boolean active = Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT EXISTS(SELECT 1 FROM public.users
              WHERE id=? AND user_type='ADMIN' AND status='ACTIVE')
            """, Boolean.class, actor));
        if (!active) throw new AccessDeniedException("Administrador indisponível");
    }
}
