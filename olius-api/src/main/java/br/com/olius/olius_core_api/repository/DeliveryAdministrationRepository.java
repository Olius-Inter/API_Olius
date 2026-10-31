package br.com.olius.olius_core_api.repository;

import br.com.olius.olius_core_api.dto.DeliveryAdministrationContracts.Detail;
import br.com.olius.olius_core_api.dto.DeliveryAdministrationContracts.Item;
import br.com.olius.olius_core_api.entity.enums.RecordStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class DeliveryAdministrationRepository {
    private static final String COLUMNS="""
        SELECT d.id,d.pev_id,d.citizen_id,u.name AS citizen_name,d.validated_by,
               d.oil_volume_liters,d.delivery_date,d.created_at,d.points_earned,
               d.record_status,d.revision
        FROM public.delivery_pev d JOIN public.users u ON u.id=d.citizen_id
        """;
    private final JdbcTemplate jdbc;
    public DeliveryAdministrationRepository(JdbcTemplate jdbc) { this.jdbc=jdbc; }

    public boolean activeAdmin(UUID id) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT EXISTS(SELECT 1 FROM public.users
                WHERE id=? AND user_type='ADMIN' AND status='ACTIVE')
            """,Boolean.class,id));
    }

    public long count() {
        return jdbc.queryForObject("SELECT count(*) FROM public.delivery_pev",Long.class);
    }

    public List<Item> page(int size,long offset) {
        return jdbc.query(COLUMNS+" ORDER BY d.created_at DESC,d.id DESC LIMIT ? OFFSET ?",
            (rs,row)->item(rs),size,offset);
    }

    public Optional<Detail> detail(UUID id) {
        return jdbc.query("""
            SELECT d.id,d.pev_id,d.citizen_id,u.name AS citizen_name,d.validated_by,
                   d.oil_volume_liters,d.delivery_date,d.created_at,d.points_earned,
                   d.record_status,d.revision,pc.balance_after,pc.calculated_at
            FROM public.delivery_pev d
            JOIN public.users u ON u.id=d.citizen_id
            JOIN public.point_calculation pc ON pc.delivery_pev_id=d.id
                AND pc.user_id=d.citizen_id AND pc.is_current
            WHERE d.id=?
            """,(rs,row)->new Detail(item(rs),rs.getLong("balance_after"),
                time(rs,"calculated_at")),id).stream().findFirst();
    }

    private static Item item(ResultSet rs) throws SQLException {
        return new Item(rs.getObject("id",UUID.class),rs.getObject("pev_id",UUID.class),
            rs.getObject("citizen_id",UUID.class),rs.getString("citizen_name"),
            rs.getObject("validated_by",UUID.class),rs.getBigDecimal("oil_volume_liters"),
            time(rs,"delivery_date"),time(rs,"created_at"),rs.getLong("points_earned"),
            RecordStatus.valueOf(rs.getString("record_status")),rs.getInt("revision"));
    }

    private static Instant time(ResultSet rs,String column) throws SQLException {
        return rs.getTimestamp(column).toInstant();
    }
}
