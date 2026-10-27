package br.com.olius.olius_core_api.service;

import br.com.olius.olius_core_api.dto.IdentityResponses.AddressView;
import br.com.olius.olius_core_api.dto.PevResponses.AdminPage;
import br.com.olius.olius_core_api.dto.PevResponses.AdminPev;
import br.com.olius.olius_core_api.dto.PevResponses.AdminPevDetail;
import br.com.olius.olius_core_api.entity.enums.PevStatus;
import br.com.olius.olius_core_api.entity.enums.UserType;
import br.com.olius.olius_core_api.exception.BusinessRuleException;
import br.com.olius.olius_core_api.exception.ResourceNotFoundException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class PevAdministrationService {
    private static final String COLUMNS = """
        SELECT p.id,p.status::text AS status,p.created_at,p.updated_at,p.approved_at,
               p.approved_by,u.id AS owner_id,u.user_type::text AS owner_type,u.name AS owner_name,
               a.id AS address_id,a.state,a.city,a.neighborhood,a.street,a.number,
               a.cep,a.complement,a.latitude,a.longitude
        """;
    private static final String SOURCE = """
        FROM pev p
        JOIN users u ON u.id = COALESCE(p.citizen_id, p.establishment_id)
        JOIN addresses a ON a.id = p.address_id
        WHERE 1=1
        """;
    private final JdbcTemplate jdbc;
    private final AuditContext audit;

    public PevAdministrationService(JdbcTemplate jdbc, AuditContext audit) {
        this.jdbc=jdbc; this.audit=audit;
    }

    @Transactional(readOnly=true)
    @PreAuthorize("hasRole('ADMIN')")
    public AdminPage list(PevStatus status, UserType ownerType, int page, int size, UUID actor) {
        requireActiveAdmin(actor);
        if(ownerType==UserType.ADMIN || (long)page*size>Integer.MAX_VALUE)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);

        StringBuilder source=new StringBuilder(SOURCE);
        List<Object> parameters=new ArrayList<>();
        if(status!=null) {
            source.append(" AND p.status = CAST(? AS approval_status_t)");
            parameters.add(status.name());
        }
        if(ownerType!=null) {
            source.append(" AND u.user_type = CAST(? AS user_type_t)");
            parameters.add(ownerType.name());
        }
        Long total=jdbc.queryForObject("SELECT count(*) "+source,Long.class,parameters.toArray());
        List<Object> pageParameters=new ArrayList<>(parameters);
        pageParameters.add(size);
        pageParameters.add((long)page*size);
        List<AdminPev> items=jdbc.query(COLUMNS+source+" ORDER BY p.created_at ASC,p.id ASC LIMIT ? OFFSET ?",
            this::map, pageParameters.toArray());
        return new AdminPage(items,page,size,total==null ? 0 : total);
    }

    @Transactional(readOnly=true)
    @PreAuthorize("hasRole('ADMIN')")
    public AdminPevDetail detail(UUID id, UUID actor) {
        requireActiveAdmin(actor);
        return findDetail(id);
    }

    @Transactional
    @PreAuthorize("hasRole('ADMIN')")
    public AdminPevDetail status(UUID id, PevStatus target, UUID actor) {
        requireActiveAdmin(actor);
        var found=jdbc.queryForList("SELECT status::text FROM pev WHERE id=? FOR UPDATE",String.class,id);
        if(found.isEmpty()) throw new ResourceNotFoundException();
        PevStatus current=PevStatus.valueOf(found.getFirst());
        boolean allowed=switch(current) {
            case PENDING -> target==PevStatus.APPROVED || target==PevStatus.REJECTED;
            case APPROVED -> target==PevStatus.INACTIVE;
            case INACTIVE -> target==PevStatus.APPROVED;
            case REJECTED -> false;
        };
        if(!allowed) throw new BusinessRuleException(BusinessRuleException.Reason.STATE_CONFLICT);

        audit.user(actor,"Gestão de PEV: "+current+" -> "+target);
        if(current==PevStatus.PENDING && target==PevStatus.APPROVED)
            jdbc.update("UPDATE pev SET status='APPROVED',approved_at=clock_timestamp(),approved_by=? WHERE id=?",actor,id);
        else
            jdbc.update("UPDATE pev SET status=CAST(? AS approval_status_t) WHERE id=?",target.name(),id);
        return findDetail(id);
    }

    private AdminPevDetail findDetail(UUID id) {
        return jdbc.query(COLUMNS+SOURCE+" AND p.id=?",this::mapDetail,id).stream()
            .findFirst().orElseThrow(ResourceNotFoundException::new);
    }

    private void requireActiveAdmin(UUID actor) {
        boolean active=Boolean.TRUE.equals(jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM users WHERE id=? AND user_type='ADMIN' AND status='ACTIVE')",
            Boolean.class,actor));
        if(!active) throw new AccessDeniedException("Administrador inativo");
    }

    private AdminPevDetail mapDetail(ResultSet rs, int row) throws SQLException {
        AdminPev summary=map(rs,row);
        return new AdminPevDetail(summary.id(),summary.status(),summary.createdAt(),
            summary.updatedAt(),summary.approvedAt(),rs.getObject("approved_by",UUID.class),
            summary.ownerId(),summary.ownerType(),summary.ownerName(),summary.address());
    }

    private AdminPev map(ResultSet rs, int row) throws SQLException {
        OffsetDateTime approved=rs.getObject("approved_at",OffsetDateTime.class);
        return new AdminPev(rs.getObject("id",UUID.class),PevStatus.valueOf(rs.getString("status")),
            rs.getObject("created_at",OffsetDateTime.class).toInstant(),
            rs.getObject("updated_at",OffsetDateTime.class).toInstant(),
            approved==null ? null : approved.toInstant(),rs.getObject("owner_id",UUID.class),
            UserType.valueOf(rs.getString("owner_type")),rs.getString("owner_name"),
            new AddressView(rs.getObject("address_id",UUID.class),rs.getString("state").trim(),
                rs.getString("city"),rs.getString("neighborhood"),rs.getString("street"),
                rs.getString("number"),rs.getString("cep").trim(),rs.getString("complement"),
                rs.getBigDecimal("latitude"),rs.getBigDecimal("longitude")));
    }
}
