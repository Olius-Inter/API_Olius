package br.com.olius.olius_core_api.repository;

import br.com.olius.olius_core_api.dto.FinancialRecoveryContracts.*;
import br.com.olius.olius_core_api.exception.*;
import br.com.olius.olius_core_api.integration.payment.RefundGateway;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class FinancialRecoveryRepository {
    private final JdbcTemplate jdbc;
    public FinancialRecoveryRepository(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    public boolean activeAdmin(UUID actor) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM users WHERE id=? AND user_type='ADMIN' AND status='ACTIVE')",Boolean.class,actor));
    }
    public Page<Charge> charges(UUID after,int size) {
        var rows=jdbc.query("SELECT * FROM billing_charge WHERE provider='STRIPE' AND status IN ('OPEN','CANCELLATION_PENDING') AND (?::uuid IS NULL OR id>?::uuid) ORDER BY id LIMIT ?",
            this::mapCharge,after,after,size+1);
        return new Page<>(List.copyOf(rows.subList(0,Math.min(size,rows.size()))),rows.size()>size ? rows.get(size-1).id() : null);
    }
    public Charge charge(UUID id) {
        var rows=jdbc.query("SELECT * FROM billing_charge WHERE id=? AND provider='STRIPE'",this::mapCharge,id);
        if(rows.isEmpty()) throw new ResourceNotFoundException();return rows.getFirst();
    }
    private Charge mapCharge(ResultSet rs,int n) throws SQLException {
        return new Charge(rs.getObject("id",UUID.class),rs.getString("status"),rs.getString("provider_charge_id"),rs.getBigDecimal("amount"),instant(rs,"expires_at"),instant(rs,"closed_at"));
    }
    public Page<Refund> refunds(UUID after,int size) {
        var ids=jdbc.query("SELECT id FROM payment_refund WHERE provider='STRIPE' AND status<>'COMPLETED' AND (?::uuid IS NULL OR id>?::uuid) ORDER BY id LIMIT ?",
            (rs,n)->rs.getObject(1,UUID.class),after,after,size+1);
        return new Page<>(ids.stream().limit(size).map(this::refund).toList(),ids.size()>size ? ids.get(size-1) : null);
    }
    public Refund refund(UUID id) {
        var rows=jdbc.query("SELECT r.*,p.billing_charge_id,p.provider_payment_id FROM payment_refund r JOIN payment p ON p.id=r.payment_id WHERE r.id=? AND r.provider='STRIPE'",(rs,n)->new Refund(
            rs.getObject("id",UUID.class),rs.getObject("billing_charge_id",UUID.class),rs.getString("provider_payment_id"),rs.getString("status"),rs.getString("provider_refund_id"),rs.getBigDecimal("amount"),rs.getString("reason"),
            instant(rs,"requested_at"),instant(rs,"completed_at"),instant(rs,"next_attempt_at"),rs.getString("last_error"),List.of()),id);
        if(rows.isEmpty()) throw new ResourceNotFoundException();var r=rows.getFirst();
        var attempts=jdbc.query("SELECT * FROM payment_refund_attempt WHERE refund_id=? ORDER BY attempt_number DESC LIMIT 20",
            (rs,n)->new Attempt(rs.getInt("attempt_number"),instant(rs,"started_at"),instant(rs,"finished_at"),rs.getString("result_status"),rs.getString("error_description")),id);
        return new Refund(r.id(),r.chargeId(),r.providerPaymentId(),r.status(),r.providerId(),r.amount(),r.reason(),r.requestedAt(),r.completedAt(),r.nextAttemptAt(),r.error(),attempts);
    }
    /** Reivindica apenas uma consulta; o ID informado não é persistido antes da verificação externa. */
    public RefundRepository.Claim claimRefund(UUID id,String providerId) {
        var rows=jdbc.queryForList("""
            SELECT r.status::text,r.provider_refund_id,r.idempotency_key,r.amount,r.next_attempt_at,
            p.provider_payment_id,p.billing_charge_id,clock_timestamp() AS now
            FROM payment_refund r JOIN payment p ON p.id=r.payment_id
            WHERE r.id=? AND r.provider='STRIPE' FOR UPDATE OF r
            """,id);
        if(rows.isEmpty()) throw new ResourceNotFoundException();var r=rows.getFirst();
        Instant now=((Timestamp)r.get("now")).toInstant();
        if("COMPLETED".equals(r.get("status")) || (r.get("provider_refund_id")!=null && !providerId.equals(r.get("provider_refund_id")))
                || ("PROCESSING".equals(r.get("status")) && r.get("next_attempt_at")!=null && ((Timestamp)r.get("next_attempt_at")).toInstant().isAfter(now)))
            throw new ResourceConflictException();
        var history=jdbc.queryForMap("SELECT COALESCE(MAX(attempt_number),0) AS last_number FROM payment_refund_attempt WHERE refund_id=?",id);
        jdbc.update("UPDATE payment_refund_attempt SET finished_at=clock_timestamp(),result_status='FAILED',error_description='REFUND_WORKER_INTERRUPTED' WHERE refund_id=? AND finished_at IS NULL",id);
        UUID attempt=UUID.randomUUID();int number=((Number)history.get("last_number")).intValue()+1;
        jdbc.update("INSERT INTO payment_refund_attempt(id,refund_id,attempt_number) VALUES (?,?,?)",attempt,id,number);
        jdbc.update("UPDATE payment_refund SET status='PROCESSING',next_attempt_at=clock_timestamp()+interval '5 minutes',last_error='REFUND_MANUAL_LOOKUP' WHERE id=?",id);
        Instant first=jdbc.queryForObject("SELECT MIN(started_at) FROM payment_refund_attempt WHERE refund_id=?",Timestamp.class,id).toInstant();
        return new RefundRepository.Claim(id,attempt,number,providerId,new RefundGateway.Request(id,(UUID)r.get("idempotency_key"),
            (UUID)r.get("billing_charge_id"),(String)r.get("provider_payment_id"),(java.math.BigDecimal)r.get("amount"),first));
    }
    private Instant instant(ResultSet rs,String name) throws SQLException { var value=rs.getTimestamp(name);return value==null ? null : value.toInstant(); }
}
