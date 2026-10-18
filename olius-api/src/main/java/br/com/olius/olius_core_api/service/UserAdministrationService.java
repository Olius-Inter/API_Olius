package br.com.olius.olius_core_api.service;
import br.com.olius.olius_core_api.entity.enums.UserStatus;
import br.com.olius.olius_core_api.exception.ResourceNotFoundException;
import br.com.olius.olius_core_api.exception.BusinessRuleException;
import org.springframework.security.access.AccessDeniedException;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
@Service
public class UserAdministrationService {
    private final JdbcTemplate jdbc;
    private final AuditContext audit;
    public UserAdministrationService(JdbcTemplate jdbc,AuditContext audit) { this.jdbc=jdbc; this.audit=audit; }
    @Transactional
    @PreAuthorize("hasRole('ADMIN')")
    public void status(UUID target,UserStatus status,UUID actor) {
        // Transaction-scoped lock serializes status changes across API instances.
        jdbc.execute("SELECT pg_advisory_xact_lock(hashtextextended('olius:active-admins',0))");
        boolean activeActor=Boolean.TRUE.equals(jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM users WHERE id=? AND user_type='ADMIN' AND status='ACTIVE')",Boolean.class,actor));
        if(!activeActor) throw new AccessDeniedException("Administrador inativo");
        var targetTypes=jdbc.queryForList("SELECT user_type::text FROM users WHERE id=?",String.class,target);
        if(targetTypes.isEmpty()) throw new ResourceNotFoundException();
        if(status==UserStatus.INACTIVE && "ADMIN".equals(targetTypes.getFirst())) {
            boolean activeTarget=Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT status='ACTIVE' FROM users WHERE id=?",Boolean.class,target));
            Long count=jdbc.queryForObject("SELECT count(*) FROM users WHERE user_type='ADMIN' AND status='ACTIVE'",Long.class);
            if(activeTarget && (count==null || count<=1))
                throw new BusinessRuleException(BusinessRuleException.Reason.STATE_CONFLICT);
        }
        audit.user(actor,"Alteração administrativa do estado da conta");
        if(jdbc.update("UPDATE users SET status=?::active_status_t WHERE id=?",status.name(),target)==0)
            throw new ResourceNotFoundException();
        // A trigger oficial revoga as sessões quando a conta fica INACTIVE.
    }
}
