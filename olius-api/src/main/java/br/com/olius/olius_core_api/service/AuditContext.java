package br.com.olius.olius_core_api.service;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
@Component
public class AuditContext {
    private final JdbcTemplate jdbc;
    public AuditContext(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    public void user(UUID id, String reason) { set("USER",id.toString(),reason); }
    public void system(String reason) { set("SYSTEM","",reason); }
    private void set(String actor, String id, String reason) {
        if(!TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Contexto de auditoria exige transação");
        jdbc.queryForObject("SELECT set_config('app.audit_actor',?,true)",String.class,actor);
        jdbc.queryForObject("SELECT set_config('app.current_user_id',?,true)",String.class,id);
        jdbc.queryForObject("SELECT set_config('app.operational_driver_id','',true)",String.class);
        jdbc.queryForObject("SELECT set_config('app.audit_reason',?,true)",String.class,reason);
    }
}
