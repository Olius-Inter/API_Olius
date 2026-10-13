package br.com.olius.olius_core_api.repository;
import br.com.olius.olius_core_api.entity.UserType;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
@Repository
public class AuthSessionRepository {
    public record Outcome(String code,UUID sessionId,UUID userId,Instant expiresAt) { }
    public record State(boolean valid,UserType type,Instant expiresAt) { }
    private final JdbcTemplate jdbc;
    public AuthSessionRepository(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    public Outcome open(UUID userId,byte[] hash) {
        return jdbc.queryForObject("SELECT * FROM open_auth_session(?,?)",(rs,row) ->
            new Outcome(rs.getString("result_code"),rs.getObject("session_id",UUID.class),userId,
                rs.getTimestamp("expires_at")==null ? null : rs.getTimestamp("expires_at").toInstant()),userId,hash);
    }
    public Outcome rotate(byte[] current,byte[] next) {
        return jdbc.queryForObject("SELECT * FROM rotate_auth_refresh_token(?,?)",(rs,row) ->
            new Outcome(rs.getString("result_code"),rs.getObject("session_id",UUID.class),
                rs.getObject("user_id",UUID.class),rs.getTimestamp("expires_at")==null ? null :
                rs.getTimestamp("expires_at").toInstant()),current,next);
    }
    public State status(UUID userId,UUID sessionId) {
        return jdbc.queryForObject("SELECT * FROM get_auth_session_status(?,?)",(rs,row) ->
            new State(rs.getBoolean("is_valid"),rs.getString("user_type")==null ? null :
                UserType.valueOf(rs.getString("user_type")),rs.getTimestamp("idle_expires_at")==null ? null :
                rs.getTimestamp("idle_expires_at").toInstant()),userId,sessionId);
    }
    public String revoke(UUID userId,UUID sessionId) {
        return jdbc.queryForObject("SELECT revoke_auth_session(?,?)",String.class,userId,sessionId);
    }
    public void revokeAll(UUID userId) {
        jdbc.queryForMap("SELECT * FROM revoke_user_auth_sessions(?)",userId);
    }
}
