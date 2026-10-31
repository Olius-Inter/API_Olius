package br.com.olius.olius_core_api.repository;

import br.com.olius.olius_core_api.dto.CollectionContracts.Period;
import br.com.olius.olius_core_api.dto.EnvironmentalContracts.*;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class EnvironmentalReadRepository {
    private final JdbcTemplate jdbc;
    public EnvironmentalReadRepository(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    public Summary summary(UUID establishment,Period period,boolean administrative) {
        var from=timestamp(period.from());var until=timestamp(period.until());
        var collections=jdbc.queryForObject("""
            SELECT count(*) AS total,count(*) FILTER(WHERE result='SUCCESSFUL') AS successful,
            count(*) FILTER(WHERE result='UNSUCCESSFUL') AS unsuccessful,
            COALESCE(sum(collected_volume_liters),0) AS volume,count(DISTINCT establishment_id) AS establishments
            FROM public.collection WHERE record_status='RECORDED'
            AND (CAST(? AS uuid) IS NULL OR establishment_id=?)
            AND (CAST(? AS timestamptz) IS NULL OR collection_date>=?)
            AND (CAST(? AS timestamptz) IS NULL OR collection_date<?)
            """,(rs,row)->new Collections(rs.getLong("total"),rs.getLong("successful"),rs.getLong("unsuccessful"),
                rs.getBigDecimal("volume"),rs.getLong("establishments")),establishment,establishment,from,from,until,until);
        // PEV de estabelecimento não torna entregas dos cidadãos parte do seu volume B2B.
        Deliveries deliveries=administrative ? deliveries(from,until) : null;
        return new Summary(period.from(),period.until(),collections,deliveries);
    }
    private Deliveries deliveries(Timestamp from,Timestamp until) {
        return jdbc.queryForObject("""
            SELECT count(*) AS total,COALESCE(sum(oil_volume_liters),0) AS volume,
            count(DISTINCT citizen_id) AS citizens,count(DISTINCT pev_id) AS pevs
            FROM public.delivery_pev WHERE record_status='RECORDED'
            AND (CAST(? AS timestamptz) IS NULL OR delivery_date>=?)
            AND (CAST(? AS timestamptz) IS NULL OR delivery_date<?)
            """,(rs,row)->new Deliveries(rs.getLong("total"),rs.getBigDecimal("volume"),rs.getLong("citizens"),rs.getLong("pevs")),from,from,until,until);
    }
    private static Timestamp timestamp(Instant value) { return value==null ? null : Timestamp.from(value); }
}
