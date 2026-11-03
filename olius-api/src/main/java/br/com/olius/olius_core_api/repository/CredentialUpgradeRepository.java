package br.com.olius.olius_core_api.repository;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class CredentialUpgradeRepository {
    private final JdbcTemplate jdbc;
    public CredentialUpgradeRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /** UPDATE condicional bloqueia o usuário e reavalia a credencial após esperar outra transação. */
    public boolean replace(UUID userId, String validatedHash, String replacement) {
        return jdbc.update("""
            UPDATE public.users SET password_hash=?
            WHERE id=? AND status='ACTIVE'::public.active_status_t AND password_hash=?
            """, replacement, userId, validatedHash) == 1;
    }
}
