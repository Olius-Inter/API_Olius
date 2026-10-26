package br.com.olius.olius_core_api.repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** SQL explícito para respeitar permissões por coluna e defaults protegidos. */
@Repository
public class BillingCheckoutRepository {
    public record Quote(UUID id,String name,String description,BigDecimal price,BigDecimal liters,int slots) { }
    public record Cycle(UUID id,UUID subscriptionId,Instant startsAt,Instant endsAt) { }
    public record Charge(UUID id,UUID ownerId,UUID planId,UUID key,String planName,BigDecimal amount,
                         Instant expiresAt,String status,String providerId,String provider,String purpose) { }
    private static final String CHARGE_COLUMNS="id,establishment_id,plan_id,idempotency_key,plan_name,amount,expires_at,status,provider_charge_id,provider,purpose";
    private final JdbcTemplate jdbc;
    public BillingCheckoutRepository(JdbcTemplate jdbc) { this.jdbc=jdbc; }

    public boolean lockActiveEstablishment(UUID owner) {
        return jdbc.query("""
                SELECT u.status::text FROM public.users u JOIN public.establishment e ON e.id=u.id
                WHERE e.id=? FOR UPDATE OF e FOR SHARE OF u
                """,(rs,n)->rs.getString(1),owner).contains("ACTIVE");
    }
    public Instant currentInstant() { return jdbc.queryForObject("SELECT clock_timestamp()",Timestamp.class).toInstant(); }
    public Instant now() { return jdbc.queryForObject("SELECT date_trunc('second',clock_timestamp())",Timestamp.class).toInstant(); }
    public List<Charge> byKey(UUID key) {
        return jdbc.query("SELECT "+CHARGE_COLUMNS+" FROM public.billing_charge WHERE idempotency_key=?",this::charge,key);
    }
    public List<Charge> lockCharge(UUID id) {
        return jdbc.query("SELECT "+CHARGE_COLUMNS+" FROM public.billing_charge WHERE id=? FOR UPDATE",this::charge,id);
    }
    public boolean hasPaidHistory(UUID owner) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM public.subscription_cycle WHERE establishment_id=?)",Boolean.class,owner));
    }
    public boolean hasOpenPeriodCharge(UUID owner) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM public.billing_charge WHERE establishment_id=?
                AND purpose IN ('INITIAL','RENEWAL') AND status IN ('OPEN','CANCELLATION_PENDING'))
                """,Boolean.class,owner));
    }
    public List<Quote> activeQuote(UUID planId) {
        return jdbc.query("""
                SELECT id,name,description,monthly_price,volume_limit_liters,collection_limit
                FROM public.subscription_plan WHERE id=? AND status='ACTIVE' FOR SHARE
                """,(rs,n)->new Quote(rs.getObject("id",UUID.class),rs.getString("name"),rs.getString("description"),
                rs.getBigDecimal("monthly_price"),rs.getBigDecimal("volume_limit_liters"),rs.getInt("collection_limit")),planId);
    }
    public UUID pendingSubscription(UUID owner) {
        var pending=jdbc.query("""
                SELECT id FROM public.establishment_subscription WHERE establishment_id=? AND status='PENDING'
                ORDER BY created_at,id LIMIT 1
                """,(rs,n)->rs.getObject(1,UUID.class),owner);
        if(!pending.isEmpty()) return pending.getFirst();
        UUID id=UUID.randomUUID();
        jdbc.update("INSERT INTO public.establishment_subscription(id,establishment_id) VALUES (?,?)",id,owner);
        return id;
    }
    public UUID initialOrder(UUID owner,UUID subscription) {
        var existing=jdbc.query("SELECT id FROM public.billing_order WHERE subscription_id=? AND purpose='INITIAL'",
                (rs,n)->rs.getObject(1,UUID.class),subscription);
        if(!existing.isEmpty()) return existing.getFirst();
        UUID id=UUID.randomUUID();
        jdbc.update("""
                INSERT INTO public.billing_order(id,establishment_id,subscription_id,purpose,benefit_key)
                VALUES (?,?,?,'INITIAL',?)
                """,id,owner,subscription,"INITIAL:"+subscription);
        return id;
    }
    public Charge insert(UUID owner,UUID order,UUID key,Quote quote,Instant expires) {
        return insertPeriod(owner,order,key,quote,expires,"INITIAL");
    }
    public Charge insertPeriod(UUID owner,UUID order,UUID key,Quote quote,Instant expires,String purpose) {
        if(!java.util.Set.of("INITIAL","RENEWAL").contains(purpose))
            throw new IllegalArgumentException("Finalidade incompatível com cobrança de período");
        UUID id=UUID.randomUUID();
        jdbc.update("""
                INSERT INTO public.billing_charge(id,billing_order_id,establishment_id,purpose,provider,
                idempotency_key,plan_id,plan_name,plan_description,quoted_monthly_price,
                quoted_volume_limit_liters,quoted_collection_limit,amount,currency,expires_at)
                VALUES (?,?,?,?::public.billing_purpose_t,'STRIPE',?,?,?,?,?,?,?,?,'BRL',?)
                """,id,order,owner,purpose,key,quote.id(),quote.name(),quote.description(),quote.price(),quote.liters(),
                quote.slots(),quote.price(),Timestamp.from(expires));
        return byKey(key).getFirst();
    }
    public List<Cycle> latestCycle(UUID owner) {
        return jdbc.query("""
                SELECT id,subscription_id,starts_at,ends_at FROM public.subscription_cycle
                WHERE establishment_id=? ORDER BY ends_at DESC,starts_at DESC,id DESC LIMIT 1
                """,(rs,n)->new Cycle(rs.getObject("id",UUID.class),rs.getObject("subscription_id",UUID.class),
                rs.getTimestamp("starts_at").toInstant(),rs.getTimestamp("ends_at").toInstant()),owner);
    }
    public boolean hasAnotherActiveSubscription(UUID owner,UUID subscription) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM public.establishment_subscription
                WHERE establishment_id=? AND id<>? AND status='ACTIVE')
                """,Boolean.class,owner,subscription));
    }
    public UUID renewalOrder(UUID owner,Cycle previous) {
        var existing=jdbc.query("SELECT id FROM public.billing_order WHERE previous_cycle_id=? AND purpose='RENEWAL'",
                (rs,n)->rs.getObject(1,UUID.class),previous.id());
        if(!existing.isEmpty()) return existing.getFirst();
        UUID id=UUID.randomUUID();
        jdbc.update("""
                INSERT INTO public.billing_order(id,establishment_id,subscription_id,purpose,benefit_key,previous_cycle_id)
                VALUES (?,?,?,'RENEWAL',?,?)
                """,id,owner,previous.subscriptionId(),"RENEWAL:"+previous.id(),previous.id());
        return id;
    }
    public void associate(UUID id,String providerId) {
        jdbc.update("UPDATE public.billing_charge SET provider_charge_id=? WHERE id=?",providerId,id);
    }
    public boolean activeOwner(UUID id) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM public.users WHERE id=? AND status='ACTIVE' AND user_type='ESTABLISHMENT')",Boolean.class,id));
    }
    private Charge charge(ResultSet rs,int row) throws SQLException {
        return new Charge(rs.getObject("id",UUID.class),rs.getObject("establishment_id",UUID.class),
                rs.getObject("plan_id",UUID.class),rs.getObject("idempotency_key",UUID.class),rs.getString("plan_name"),
                rs.getBigDecimal("amount"),rs.getTimestamp("expires_at").toInstant(),rs.getString("status"),
                rs.getString("provider_charge_id"),rs.getString("provider"),rs.getString("purpose"));
    }
}
