package br.com.olius.olius_core_api.repository;

import br.com.olius.olius_core_api.integration.payment.ChargeReconciliationGateway.*;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ChargeReconciliationRepository {
    private final JdbcTemplate jdbc;
    public ChargeReconciliationRepository(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    public List<UUID> due(UUID after) {
        String query="SELECT id FROM public.billing_charge WHERE provider='STRIPE' AND status IN ('OPEN','CANCELLATION_PENDING')";
        if(after!=null) query+=" AND id>?";
        query+=" ORDER BY id LIMIT 10";
        return after==null ? jdbc.query(query,(rs,n)->rs.getObject(1,UUID.class)) : jdbc.query(query,(rs,n)->rs.getObject(1,UUID.class),after);
    }
    public Request snapshot(UUID id) {
        return jdbc.queryForObject("SELECT id,idempotency_key,amount,created_at,expires_at,provider_charge_id FROM public.billing_charge WHERE id=?",
            (rs,n)->new Request(rs.getObject("id",UUID.class),rs.getObject("idempotency_key",UUID.class),rs.getBigDecimal("amount"),
                rs.getTimestamp("created_at").toInstant(),rs.getTimestamp("expires_at").toInstant(),rs.getString("provider_charge_id")),id);
    }
    public void state(UUID id,String state) {
        jdbc.queryForObject("SELECT public.set_billing_charge_external_status(?,?::public.charge_status_t)",Object.class,id,state);
    }
    public boolean exists(UUID id) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM public.billing_charge WHERE id=? AND provider='STRIPE')",Boolean.class,id));
    }
}
