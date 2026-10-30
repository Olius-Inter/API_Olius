package br.com.olius.olius_core_api.repository;

import br.com.olius.olius_core_api.dto.AdminInvitationResponses.*;
import br.com.olius.olius_core_api.entity.enums.UserStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AdminInvitationRepository {
    private static final String COLUMNS = """
        id,name,email,issued_at,expires_at,invited_by,is_bootstrap,
        accepted_at,accepted_user_id,revoked_at,revoked_by,
        CASE WHEN accepted_at IS NOT NULL THEN 'ACCEPTED'
             WHEN revoked_at IS NOT NULL THEN 'REVOKED'
             WHEN expires_at<=clock_timestamp() THEN 'EXPIRED'
             ELSE 'AVAILABLE' END AS invitation_state
        """;
    private static final String LIST_SOURCE =
            " FROM (SELECT " + COLUMNS + " FROM public.admin_invitation) invitations"
            + " WHERE (CAST(? AS text) IS NULL OR invitation_state=?)";
    private static final String COUNT_QUERY = "SELECT count(*)" + LIST_SOURCE;
    private static final String PAGE_QUERY =
            "SELECT id,name,email,issued_at,expires_at,invited_by,is_bootstrap,"
            + "accepted_at,accepted_user_id,revoked_at,revoked_by,invitation_state"
            + LIST_SOURCE + " ORDER BY issued_at DESC,id DESC LIMIT ? OFFSET ?";
    private final JdbcTemplate jdbc;

    public AdminInvitationRepository(JdbcTemplate jdbc) { this.jdbc=jdbc; }

    public UUID issue(UUID actor, String name, String email, byte[] hash) {
        return invoke(() -> jdbc.queryForObject("SELECT public.issue_admin_invitation(?,?,?,?)",
                UUID.class, actor, name, email, hash));
    }

    public UUID accept(byte[] hash, String passwordHash) {
        return invoke(() -> jdbc.queryForObject("SELECT public.accept_admin_invitation(?,?)",
                UUID.class, hash, passwordHash));
    }

    public boolean revoke(UUID actor, UUID id) {
        return Boolean.TRUE.equals(invoke(() -> jdbc.queryForObject(
                "SELECT public.revoke_admin_invitation(?,?)",Boolean.class,actor,id)));
    }

    public void status(UUID actor, UUID target, UserStatus status) {
        invoke(() -> jdbc.queryForObject("SELECT public.set_admin_status(?,?,?::public.active_status_t)",
                Object.class,actor,target,status.name()));
    }

    public Invitation get(UUID id) {
        return jdbc.queryForObject("SELECT "+COLUMNS+" FROM public.admin_invitation WHERE id=?",
                this::map,id);
    }

    public Page list(State state, int page, int size) {
        String stateName=state==null ? null : state.name();
        Long total=jdbc.queryForObject(COUNT_QUERY,Long.class,stateName,stateName);
        var items=jdbc.query(PAGE_QUERY,this::map,stateName,stateName,size,(long)page*size);
        return new Page(items,page,size,total==null ? 0 : total);
    }

    private Invitation map(ResultSet rs,int row) throws SQLException {
        return new Invitation(rs.getObject("id",UUID.class),rs.getString("name"),rs.getString("email"),
                time(rs,"issued_at"),time(rs,"expires_at"),rs.getObject("invited_by",UUID.class),
                rs.getBoolean("is_bootstrap"),time(rs,"accepted_at"),rs.getObject("accepted_user_id",UUID.class),
                time(rs,"revoked_at"),rs.getObject("revoked_by",UUID.class),State.valueOf(rs.getString("invitation_state")));
    }

    private Instant time(ResultSet rs,String field) throws SQLException {
        var timestamp=rs.getTimestamp(field);
        return timestamp==null ? null : timestamp.toInstant();
    }

    private <T> T invoke(Supplier<T> call) {
        try { return call.get(); }
        catch (DataAccessException failure) { throw AdminRoutineErrors.translate(failure); }
    }
}
