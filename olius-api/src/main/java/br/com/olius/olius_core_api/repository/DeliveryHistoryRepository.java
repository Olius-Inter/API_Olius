package br.com.olius.olius_core_api.repository;

import br.com.olius.olius_core_api.dto.DeliveryHistoryContracts.Item;
import br.com.olius.olius_core_api.entity.enums.RecordStatus;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class DeliveryHistoryRepository {
    private final JdbcTemplate jdbc;

    public DeliveryHistoryRepository(JdbcTemplate jdbc) { this.jdbc=jdbc; }

    public long count(UUID citizen) {
        return jdbc.queryForObject("SELECT count(*) FROM public.delivery_pev WHERE citizen_id=?",
            Long.class,citizen);
    }

    public List<Item> page(UUID citizen, int size, long offset) {
        return jdbc.query("""
            SELECT id,pev_id,oil_volume_liters,delivery_date,created_at,
                   points_earned,record_status,revision
            FROM public.delivery_pev WHERE citizen_id=?
            ORDER BY delivery_date DESC,id DESC LIMIT ? OFFSET ?
            """,(rs,row)->new Item(rs.getObject("id",UUID.class),rs.getObject("pev_id",UUID.class),
                rs.getBigDecimal("oil_volume_liters"),rs.getTimestamp("delivery_date").toInstant(),
                rs.getTimestamp("created_at").toInstant(),rs.getLong("points_earned"),
                RecordStatus.valueOf(rs.getString("record_status")),rs.getInt("revision")),
            citizen,size,offset);
    }
}
