package br.com.olius.olius_core_api.repository;

import br.com.olius.olius_core_api.exception.PaymentProviderUnavailableException;
import br.com.olius.olius_core_api.integration.payment.PaymentVerificationGateway.VerifiedPayment;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class VerifiedPaymentRepository {
    private final JdbcTemplate jdbc;
    public VerifiedPaymentRepository(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    public UUID record(VerifiedPayment verified) {
        var charges=jdbc.queryForList("""
                SELECT id,billing_order_id,establishment_id,provider,amount,currency FROM public.billing_charge WHERE id=?
                """,verified.chargeId());
        if(charges.isEmpty()) throw new PaymentProviderUnavailableException();
        var charge=charges.getFirst();
        if(!"STRIPE".equals(charge.get("provider")) || !"BRL".equals(verified.currency())
                || !verified.currency().equals(charge.get("currency"))
                || verified.amount().compareTo((BigDecimal)charge.get("amount"))!=0)
            throw new PaymentProviderUnavailableException();
        var now=jdbc.queryForObject("SELECT CURRENT_TIMESTAMP",Timestamp.class).toInstant();
        if(verified.paidAt().isAfter(now)) throw new PaymentProviderUnavailableException();
        jdbc.update("""
                INSERT INTO public.payment(id,billing_charge_id,billing_order_id,establishment_id,
                provider,provider_payment_id,amount,currency,paid_at,verified_at)
                VALUES (?,?,?,?,'STRIPE',?,?,'BRL',?,CURRENT_TIMESTAMP)
                ON CONFLICT (provider,provider_payment_id) DO NOTHING
                """,UUID.randomUUID(),verified.chargeId(),charge.get("billing_order_id"),charge.get("establishment_id"),
                verified.paymentId(),verified.amount(),Timestamp.from(verified.paidAt()));
        var payment=jdbc.queryForMap("SELECT id,billing_charge_id,amount FROM public.payment WHERE provider='STRIPE' AND provider_payment_id=?",verified.paymentId());
        if(!verified.chargeId().equals(payment.get("billing_charge_id"))
                || verified.amount().compareTo((BigDecimal)payment.get("amount"))!=0)
            throw new PaymentProviderUnavailableException();
        return (UUID)payment.get("id");
    }
    public void apply(UUID paymentId) {
        var outcome=jdbc.queryForMap("CALL public.apply_verified_payment(?,NULL,NULL,NULL)",paymentId);
        // Ciclo/aplicação ou reembolso são resultados distintos, ambos previstos pelo contrato.
        if(outcome.get("p_payment_application_id")==null && outcome.get("p_refund_id")==null)
            throw new IllegalStateException("Aplicação de pagamento sem resultado reconhecido");
    }
}
