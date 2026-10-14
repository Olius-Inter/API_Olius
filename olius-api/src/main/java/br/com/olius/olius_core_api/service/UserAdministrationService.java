package br.com.olius.olius_core_api.service;
import br.com.olius.olius_core_api.entity.UserStatus;
import br.com.olius.olius_core_api.exception.ResourceNotFoundException;
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
        audit.user(actor,"Alteração administrativa do estado da conta");
        if(jdbc.update("UPDATE users SET status=?::active_status_t WHERE id=?",status.name(),target)==0)
            throw new ResourceNotFoundException();
        // A trigger oficial revoga as sessões quando a conta fica INACTIVE.
    }
}
