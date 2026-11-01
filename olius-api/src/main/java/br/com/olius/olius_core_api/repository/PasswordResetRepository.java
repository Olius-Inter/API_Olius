package br.com.olius.olius_core_api.repository;

import java.time.Duration;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class PasswordResetRepository {
    public record Issued(String code,Instant expiresAt) { }
    private final JdbcTemplate jdbc;
    public PasswordResetRepository(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    public Issued issue(String email,byte[] tokenHash,Duration ttl) {
        return jdbc.queryForObject("SELECT result_code,expires_at FROM public.issue_password_reset(?,?,?::interval)",
            (rs,row) -> new Issued(rs.getString("result_code"),rs.getTimestamp("expires_at")==null ? null
                : rs.getTimestamp("expires_at").toInstant()),email,tokenHash,ttl.toString());
    }
    public String complete(byte[] tokenHash,String passwordHash) {
        return jdbc.queryForObject("SELECT public.complete_password_reset(?,?)",String.class,tokenHash,passwordHash);
    }
}
