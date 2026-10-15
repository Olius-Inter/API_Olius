package br.com.olius.olius_core_api.service;
import br.com.olius.olius_core_api.dto.IdentityRequests.AdminRegistration;
import br.com.olius.olius_core_api.entity.*;
import jakarta.persistence.EntityManager;
import jakarta.validation.Validator;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
@Service
public class FirstAdminService {
    private final EntityManager em;
    private final JdbcTemplate jdbc;
    private final AuditContext audit;
    private final PasswordEncoder passwords;
    private final Validator validator;
    public FirstAdminService(EntityManager em,JdbcTemplate jdbc,AuditContext audit,PasswordEncoder passwords,Validator validator) {
        this.em=em; this.jdbc=jdbc; this.audit=audit; this.passwords=passwords; this.validator=validator;
    }
    @Transactional
    public UUID provision(AdminRegistration request) {
        if(!validator.validate(request).isEmpty()) throw new IllegalArgumentException("Dados do administrador inicial inválidos");
        // Evita dois provisionamentos simultâneos. Não permite reabrir o bootstrap após criar qualquer ADM.
        jdbc.execute("SELECT pg_advisory_xact_lock(hashtextextended('olius:first-admin',0))");
        if(Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM users WHERE user_type='ADMIN')",Boolean.class)))
            throw new IllegalStateException("Já existe administrador; utilize o fluxo administrativo autenticado");
        audit.system("Provisionamento inicial por operador autorizado");
        UUID id=UUID.randomUUID(); em.persist(new User(id,request.name().strip(),EmailAddress.normalize(request.email()),
            passwords.encode(request.password()),UserType.ADMIN)); em.flush(); return id;
    }
}
