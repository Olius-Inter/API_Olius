package br.com.olius.olius_core_api.repository;

import br.com.olius.olius_core_api.integration.payment.RefundGateway;
import br.com.olius.olius_core_api.integration.payment.RefundGateway.FailureKind;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class RefundRepository {
    public record Claim(UUID refundId,UUID attemptId,int number,String providerId,RefundGateway.Request request) { }
    private static final String DUE="""
            ((r.status='REQUESTED' AND (r.next_attempt_at IS NULL OR r.next_attempt_at<=clock_timestamp()))
            OR (r.status IN ('PROCESSING','FAILED') AND r.next_attempt_at<=clock_timestamp()))
            """;
    private final JdbcTemplate jdbc;
    public RefundRepository(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    public List<UUID> due() {
        return jdbc.query("SELECT r.id FROM public.payment_refund r WHERE r.provider='STRIPE' AND "+DUE+
            " ORDER BY r.requested_at,r.id LIMIT 10",(rs,n)->rs.getObject(1,UUID.class));
    }
    public List<Claim> claim(UUID id) {
        var rows=jdbc.queryForList("""
                SELECT r.id,r.idempotency_key,r.provider_refund_id,r.last_error,r.amount,p.provider_payment_id,p.billing_charge_id
                FROM public.payment_refund r JOIN public.payment p ON p.id=r.payment_id
                WHERE r.id=? AND r.provider='STRIPE' AND
                """+DUE+" FOR UPDATE OF r SKIP LOCKED",id);
        if(rows.isEmpty()) return List.of();
        var row=rows.getFirst();
        Instant now=jdbc.queryForObject("SELECT clock_timestamp()",Timestamp.class).toInstant();
        var history=jdbc.queryForMap("SELECT MIN(started_at) AS first_at,COALESCE(MAX(attempt_number),0) AS last_number FROM public.payment_refund_attempt WHERE refund_id=?",id);
        Instant first=history.get("first_at")==null ? now : ((Timestamp)history.get("first_at")).toInstant();
        jdbc.update("""
                UPDATE public.payment_refund_attempt SET finished_at=clock_timestamp(),result_status='FAILED',
                error_description='REFUND_WORKER_INTERRUPTED' WHERE refund_id=? AND finished_at IS NULL
                """,id);
        if(row.get("provider_refund_id")==null && ("REFUND_MANUAL_LOOKUP".equals(row.get("last_error"))
                || !now.plusSeconds(30).isBefore(first.plus(Duration.ofHours(24))))) {
            jdbc.update("UPDATE public.payment_refund SET status='FAILED',next_attempt_at=NULL,last_error='REFUND_RECONCILIATION_REQUIRED' WHERE id=?",id);
            return List.of();
        }
        int number=((Number)history.get("last_number")).intValue()+1;
        UUID attempt=UUID.randomUUID();
        jdbc.update("INSERT INTO public.payment_refund_attempt(id,refund_id,attempt_number) VALUES (?,?,?)",attempt,id,number);
        jdbc.update("UPDATE public.payment_refund SET status='PROCESSING',next_attempt_at=clock_timestamp()+interval '5 minutes',last_error=NULL WHERE id=?",id);
        first=jdbc.queryForObject("SELECT MIN(started_at) FROM public.payment_refund_attempt WHERE refund_id=?",Timestamp.class,id).toInstant();
        var request=new RefundGateway.Request(id,(UUID)row.get("idempotency_key"),(UUID)row.get("billing_charge_id"),
            (String)row.get("provider_payment_id"),(java.math.BigDecimal)row.get("amount"),first);
        return List.of(new Claim(id,attempt,number,(String)row.get("provider_refund_id"),request));
    }
    public boolean lockCurrent(Claim claim) {
        return !jdbc.query("""
                SELECT r.id FROM public.payment_refund r WHERE r.id=? AND r.status='PROCESSING'
                AND EXISTS(SELECT 1 FROM public.payment_refund_attempt a WHERE a.id=? AND a.refund_id=r.id
                  AND a.finished_at IS NULL AND a.attempt_number=(SELECT MAX(b.attempt_number) FROM public.payment_refund_attempt b WHERE b.refund_id=r.id))
                FOR UPDATE OF r
                """,(rs,n)->rs.getObject(1,UUID.class),claim.refundId(),claim.attemptId()).isEmpty();
    }
    public void save(Claim claim,RefundGateway.Result result) {
        if(result==null || result.providerId()==null || !result.providerId().matches("re_[A-Za-z0-9_]+") || result.status()==null
                || (claim.providerId()!=null && !claim.providerId().equals(result.providerId())))
            throw new RefundGateway.Failure(FailureKind.REVIEW_REQUIRED);
        String state=switch(result.status()) {
            case SUCCEEDED -> "COMPLETED";
            case PENDING -> "PROCESSING";
            case FAILED,CANCELED,REQUIRES_ACTION -> "FAILED";
        };
        String error="FAILED".equals(state) ? "REFUND_PROVIDER_"+result.status().name() : null;
        jdbc.update("""
                UPDATE public.payment_refund SET provider_refund_id=?,status=?::public.refund_status_t,
                completed_at=CASE WHEN ?='COMPLETED' THEN clock_timestamp() ELSE NULL END,
                next_attempt_at=CASE WHEN ?='PROCESSING' THEN clock_timestamp()+interval '5 minutes' ELSE NULL END,
                last_error=? WHERE id=?
                """,result.providerId(),state,state,state,error,claim.refundId());
        finish(claim,state,result.requestId(),error);
    }
    public void fail(Claim claim,FailureKind kind) {
        jdbc.update("""
                UPDATE public.payment_refund SET status='FAILED',next_attempt_at=
                CASE WHEN ? THEN clock_timestamp()+interval '5 minutes' ELSE NULL END,last_error=? WHERE id=?
                """,kind==FailureKind.TEMPORARY,"REFUND_"+kind.name(),claim.refundId());
        finish(claim,"FAILED",null,"REFUND_"+kind.name());
    }
    private void finish(Claim claim,String state,String requestId,String error) {
        jdbc.update("""
                UPDATE public.payment_refund_attempt SET finished_at=clock_timestamp(),result_status=?::public.refund_status_t,
                provider_request_id=?,error_description=? WHERE id=? AND finished_at IS NULL
                """,state,requestId,error,claim.attemptId());
    }
}
