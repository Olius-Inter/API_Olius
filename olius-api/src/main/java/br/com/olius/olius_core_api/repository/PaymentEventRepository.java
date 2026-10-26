package br.com.olius.olius_core_api.repository;

import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class PaymentEventRepository {
    public record Claim(UUID id,String eventId,String paymentId,int attempt) { }
    private final JdbcTemplate jdbc;
    public PaymentEventRepository(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    public void receive(String eventId,String paymentId) {
        jdbc.update("""
                INSERT INTO public.payment_provider_event(id,provider,provider_event_id,event_type,provider_payment_id)
                VALUES (?,'STRIPE',?,'payment_intent.succeeded',?)
                ON CONFLICT (provider,provider_event_id) DO NOTHING
                """,UUID.randomUUID(),eventId,paymentId);
        var stored=jdbc.queryForMap("SELECT event_type,provider_payment_id FROM public.payment_provider_event WHERE provider='STRIPE' AND provider_event_id=?",eventId);
        if(!"payment_intent.succeeded".equals(stored.get("event_type")) || !paymentId.equals(stored.get("provider_payment_id")))
            throw new br.com.olius.olius_core_api.exception.InvalidWebhookException();
    }
    public List<UUID> due() {
        return jdbc.query("""
                SELECT id FROM public.payment_provider_event WHERE provider='STRIPE' AND
                ((status IN ('PENDING','FAILED') AND (next_attempt_at IS NULL OR next_attempt_at<=clock_timestamp()))
                OR (status='PROCESSING' AND next_attempt_at<=clock_timestamp()))
                ORDER BY received_at,id LIMIT 10
                """,(rs,n)->rs.getObject(1,UUID.class));
    }
    public List<Claim> claim(UUID id) {
        return jdbc.query("""
                UPDATE public.payment_provider_event SET status='PROCESSING',attempts=attempts+1,
                next_attempt_at=clock_timestamp()+interval '5 minutes',last_error=NULL
                WHERE id=? AND provider='STRIPE' AND
                ((status IN ('PENDING','FAILED') AND (next_attempt_at IS NULL OR next_attempt_at<=clock_timestamp()))
                OR (status='PROCESSING' AND next_attempt_at<=clock_timestamp()))
                RETURNING id,provider_event_id,provider_payment_id,attempts
                """,(rs,n)->new Claim(rs.getObject(1,UUID.class),rs.getString(2),rs.getString(3),rs.getInt(4)),id);
    }
    public boolean lockCurrent(Claim claim) {
        return !jdbc.query("""
                SELECT id FROM public.payment_provider_event WHERE id=? AND status='PROCESSING'
                AND attempts=? FOR UPDATE
                """,(rs,n)->rs.getObject(1,UUID.class),claim.id(),claim.attempt()).isEmpty();
    }
    public void processed(Claim claim,UUID paymentId) {
        jdbc.update("""
                UPDATE public.payment_provider_event SET status='PROCESSED',payment_id=?,
                processed_at=clock_timestamp(),next_attempt_at=NULL,last_error=NULL WHERE id=? AND attempts=?
                """,paymentId,claim.id(),claim.attempt());
    }
    public void failed(Claim claim) {
        jdbc.update("""
                UPDATE public.payment_provider_event SET status='FAILED',next_attempt_at=clock_timestamp()+interval '5 minutes',
                last_error='PAYMENT_PROCESSING_FAILED' WHERE id=? AND attempts=? AND status='PROCESSING'
                """,claim.id(),claim.attempt());
    }
}
