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
    private static final String FIELD_STATUS="status";
    private static final String FIELD_AMOUNT="amount";
    private static final String FIELD_PROVIDER_REFUND_ID="provider_refund_id";
    private static final String FIELD_NEXT_ATTEMPT_AT="next_attempt_at";
    private final JdbcTemplate jdbc;
    public FinancialRecoveryRepository(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    /** Verifica o perfil e o estado atuais no banco, além do papel apresentado pelo token. */
    public boolean activeAdmin(UUID actor) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM users WHERE id=? AND user_type='ADMIN' AND status='ACTIVE')",Boolean.class,actor));
    }
    /** Pagina cobranças ainda abertas por UUID, retornando um cursor quando existir outra página. */
    public Page<Charge> charges(UUID after,int size) {
        var rows=jdbc.query("SELECT * FROM billing_charge WHERE provider='STRIPE' AND status IN ('OPEN','CANCELLATION_PENDING') AND (?::uuid IS NULL OR id>?::uuid) ORDER BY id LIMIT ?",
            this::mapCharge,after,after,size+1);
        return new Page<>(List.copyOf(rows.subList(0,Math.min(size,rows.size()))),rows.size()>size ? rows.get(size-1).id() : null);
    }
    /** Retorna apenas os campos técnicos da cobrança Stripe ou informa recurso inexistente. */
    public Charge charge(UUID id) {
        var rows=jdbc.query("SELECT * FROM billing_charge WHERE id=? AND provider='STRIPE'",this::mapCharge,id);
        if(rows.isEmpty()) { throw new ResourceNotFoundException(); }
        return rows.getFirst();
    }
    private Charge mapCharge(ResultSet rs,int n) throws SQLException {
        return new Charge(rs.getObject("id",UUID.class),rs.getString(FIELD_STATUS),rs.getString("provider_charge_id"),rs.getBigDecimal(FIELD_AMOUNT),instant(rs,"expires_at"),instant(rs,"closed_at"));
    }
    /** Pagina devoluções não concluídas, incluindo tentativas e pendências de análise. */
    public Page<Refund> refunds(UUID after,int size) {
        var ids=jdbc.query("SELECT id FROM payment_refund WHERE provider='STRIPE' AND status<>'COMPLETED' AND (?::uuid IS NULL OR id>?::uuid) ORDER BY id LIMIT ?",
            (rs,n)->rs.getObject(1,UUID.class),after,after,size+1);
        return new Page<>(ids.stream().limit(size).map(this::refund).toList(),ids.size()>size ? ids.get(size-1) : null);
    }
    /** Consulta o vínculo financeiro e as últimas vinte tentativas sem dados pessoais do contratante. */
    public Refund refund(UUID id) {
        var rows=jdbc.query("SELECT r.*,p.billing_charge_id,p.provider_payment_id FROM payment_refund r JOIN payment p ON p.id=r.payment_id WHERE r.id=? AND r.provider='STRIPE'",(rs,n)->new Refund(
            rs.getObject("id",UUID.class),rs.getObject("billing_charge_id",UUID.class),rs.getString("provider_payment_id"),rs.getString(FIELD_STATUS),rs.getString(FIELD_PROVIDER_REFUND_ID),rs.getBigDecimal(FIELD_AMOUNT),rs.getString("reason"),
            instant(rs,"requested_at"),instant(rs,"completed_at"),instant(rs,FIELD_NEXT_ATTEMPT_AT),rs.getString("last_error"),List.of()),id);
        if(rows.isEmpty()) { throw new ResourceNotFoundException(); }
        var r=rows.getFirst();
        var attempts=jdbc.query("SELECT * FROM payment_refund_attempt WHERE refund_id=? ORDER BY attempt_number DESC LIMIT 20",
            (rs,n)->new Attempt(rs.getInt("attempt_number"),instant(rs,"started_at"),instant(rs,"finished_at"),rs.getString("result_status"),rs.getString("error_description")),id);
        return new Refund(r.id(),r.chargeId(),r.providerPaymentId(),r.status(),r.providerId(),r.amount(),r.reason(),r.requestedAt(),r.completedAt(),r.nextAttemptAt(),r.error(),attempts);
    }
    /** Reivindica apenas uma consulta; o ID informado não é persistido antes da verificação externa. */
    public RefundRepository.Claim claimRefund(UUID id,String providerId) {
        var rows=jdbc.queryForList("""
            SELECT r.status::text,r.provider_refund_id,r.idempotency_key,r.amount,r.next_attempt_at,r.last_error,
            p.provider_payment_id,p.billing_charge_id,clock_timestamp() AS now
            FROM payment_refund r JOIN payment p ON p.id=r.payment_id
            WHERE r.id=? AND r.provider='STRIPE' FOR UPDATE OF r
            """,id);
        if(rows.isEmpty()) { throw new ResourceNotFoundException(); }
        var r=rows.getFirst();
        Instant now=((Timestamp)r.get("now")).toInstant();
        if("COMPLETED".equals(r.get(FIELD_STATUS)) || (r.get(FIELD_PROVIDER_REFUND_ID)!=null && !providerId.equals(r.get(FIELD_PROVIDER_REFUND_ID)))
                || ("PROCESSING".equals(r.get(FIELD_STATUS)) && r.get(FIELD_NEXT_ATTEMPT_AT)!=null && ((Timestamp)r.get(FIELD_NEXT_ATTEMPT_AT)).toInstant().isAfter(now)))
            throw new ResourceConflictException();
        var history=jdbc.queryForMap("SELECT COALESCE(MAX(attempt_number),0) AS last_number FROM payment_refund_attempt WHERE refund_id=?",id);
        jdbc.update("UPDATE payment_refund_attempt SET finished_at=clock_timestamp(),result_status='FAILED',error_description='REFUND_WORKER_INTERRUPTED' WHERE refund_id=? AND finished_at IS NULL",id);
        var previous=new RefundRepository.PreviousState((String)r.get(FIELD_STATUS),
            r.get(FIELD_NEXT_ATTEMPT_AT)==null ? null : ((Timestamp)r.get(FIELD_NEXT_ATTEMPT_AT)).toInstant(),(String)r.get("last_error"));
        UUID attempt=UUID.randomUUID();
        int number=((Number)history.get("last_number")).intValue()+1;
        jdbc.update("INSERT INTO payment_refund_attempt(id,refund_id,attempt_number) VALUES (?,?,?)",attempt,id,number);
        jdbc.update("UPDATE payment_refund SET status='PROCESSING',next_attempt_at=clock_timestamp()+interval '5 minutes',last_error='REFUND_MANUAL_LOOKUP' WHERE id=?",id);
        Instant first=jdbc.queryForObject("SELECT MIN(started_at) FROM payment_refund_attempt WHERE refund_id=?",Timestamp.class,id).toInstant();
        return new RefundRepository.Claim(id,attempt,number,providerId,new RefundGateway.Request(id,(UUID)r.get("idempotency_key"),
            (UUID)r.get("billing_charge_id"),(String)r.get("provider_payment_id"),(java.math.BigDecimal)r.get(FIELD_AMOUNT),first),previous);
    }
    private Instant instant(ResultSet rs,String name) throws SQLException { var value=rs.getTimestamp(name);return value==null ? null : value.toInstant(); }
}
