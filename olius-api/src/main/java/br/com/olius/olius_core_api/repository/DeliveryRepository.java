package br.com.olius.olius_core_api.repository;

import br.com.olius.olius_core_api.dto.DeliveryContracts.View;
import br.com.olius.olius_core_api.dto.DeliveryContracts.PevItem;
import br.com.olius.olius_core_api.entity.enums.RecordStatus;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class DeliveryRepository {
    private static final String VIEW = """
        SELECT d.id,d.pev_id,d.citizen_id,d.oil_volume_liters,d.delivery_date,
               d.created_at,d.points_earned,d.record_status,d.revision,
               pc.balance_after
        FROM public.delivery_pev d
        JOIN public.point_calculation pc ON pc.delivery_pev_id=d.id
             AND pc.user_id=d.citizen_id AND pc.is_current
        """;
    private final JdbcTemplate jdbc;
    public DeliveryRepository(JdbcTemplate jdbc) { this.jdbc=jdbc; }

    /** Chamar somente dentro da transação do registro, antes de consultar a chave. */
    public void lock(UUID internalKey) {
        jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(?::text,0))",
            Object.class,internalKey.toString());
    }

    /** O filtro do validador protege a leitura mesmo com a chave interna vinculada. */
    public Optional<Existing> existing(UUID internalKey,UUID validator) {
        return jdbc.query("""
            SELECT id,citizen_id FROM public.delivery_pev
            WHERE idempotency_key=? AND validated_by=?
            """,(rs,row)->new Existing(rs.getObject("id",UUID.class),
                rs.getObject("citizen_id",UUID.class)),internalKey,validator)
            .stream().findFirst();
    }

    public UUID record(UUID internalKey,UUID citizen,UUID pev,UUID validator,
                       java.math.BigDecimal volume,Instant deliveryDate) {
        return jdbc.execute((java.sql.Connection connection)-> {
            try(var statement=connection.prepareStatement(
                "CALL public.record_pev_delivery(?,?,?,?,?,?,NULL)")) {
                statement.setObject(1,internalKey);
                statement.setObject(2,citizen);
                statement.setObject(3,pev);
                statement.setObject(4,validator);
                statement.setBigDecimal(5,volume);
                statement.setTimestamp(6,Timestamp.from(deliveryDate));
                try(var rows=statement.executeQuery()) {
                    if(!rows.next()) throw new IllegalStateException("Procedure sem ID de entrega");
                    return rows.getObject(1,UUID.class);
                }
            }
        });
    }

    /** Releitura após a procedure para observar pontos e revisão calculados pelo PostgreSQL. */
    public Optional<View> view(UUID id,UUID validator) {
        return jdbc.query(VIEW+" WHERE d.id=? AND d.validated_by=?",
            (rs,row)->new View(rs.getObject("id",UUID.class),rs.getObject("pev_id",UUID.class),
                rs.getObject("citizen_id",UUID.class),rs.getBigDecimal("oil_volume_liters"),
                time(rs.getTimestamp("delivery_date")),time(rs.getTimestamp("created_at")),
                rs.getLong("points_earned"),RecordStatus.valueOf(rs.getString("record_status")),
                rs.getInt("revision"),rs.getLong("balance_after")),id,validator)
            .stream().findFirst();
    }

    public long countForPev(UUID pevId) {
        return jdbc.queryForObject("SELECT count(*) FROM public.delivery_pev WHERE pev_id=?",Long.class,pevId);
    }

    public java.util.List<PevItem> pageForPev(UUID pevId,int size,long offset) {
        return jdbc.query("""
            SELECT d.id,d.citizen_id,u.name AS citizen_name,d.oil_volume_liters,
                   d.delivery_date,d.created_at,d.points_earned,d.record_status,d.revision
            FROM public.delivery_pev d JOIN public.users u ON u.id=d.citizen_id
            WHERE d.pev_id=? ORDER BY d.created_at DESC,d.id DESC LIMIT ? OFFSET ?
            """,(rs,row)->new PevItem(rs.getObject("id",UUID.class),
                rs.getObject("citizen_id",UUID.class),rs.getString("citizen_name"),
                rs.getBigDecimal("oil_volume_liters"),time(rs.getTimestamp("delivery_date")),
                time(rs.getTimestamp("created_at")),rs.getLong("points_earned"),
                RecordStatus.valueOf(rs.getString("record_status")),rs.getInt("revision")),
            pevId,size,offset);
    }

    private static Instant time(Timestamp value) { return value.toInstant(); }
    public record Existing(UUID id,UUID citizenId) { }
}
