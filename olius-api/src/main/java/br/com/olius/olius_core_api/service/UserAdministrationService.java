package br.com.olius.olius_core_api.service;

import br.com.olius.olius_core_api.entity.enums.UserStatus;
import br.com.olius.olius_core_api.exception.ResourceNotFoundException;
import br.com.olius.olius_core_api.repository.AdminInvitationRepository;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserAdministrationService {
    private final JdbcTemplate jdbc;
    private final AuditContext audit;
    private final AdminRegistry registry;
    private final AdminInvitationRepository admin;

    public UserAdministrationService(JdbcTemplate jdbc,AuditContext audit,AdminRegistry registry,
            AdminInvitationRepository admin) {
        this.jdbc=jdbc; this.audit=audit; this.registry=registry; this.admin=admin;
    }

    @Transactional
    @PreAuthorize("hasRole('ADMIN')")
    public void status(UUID target,UserStatus status,UUID actor) {
        registry.lock(); registry.requireActive(actor);
        var types=jdbc.queryForList("SELECT user_type::text FROM public.users WHERE id=?",String.class,target);
        if(types.isEmpty()) throw new ResourceNotFoundException();
        if("ADMIN".equals(types.getFirst())) {
            admin.status(actor,target,status);
        } else {
            audit.user(actor,"Alteração administrativa do estado da conta");
            if(jdbc.update("UPDATE public.users SET status=?::public.active_status_t WHERE id=?",status.name(),target)==0)
                throw new ResourceNotFoundException();
        }
    }
}
