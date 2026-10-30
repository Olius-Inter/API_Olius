package br.com.olius.olius_core_api.repository;

import br.com.olius.olius_core_api.dto.CollectionContracts.*;
import br.com.olius.olius_core_api.entity.enums.*;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class CollectionReadRepository {
    private static final String SELECT="""
        SELECT c.*,ARRAY(SELECT r.code::text FROM public.collection_failure f
        JOIN public.collection_failure_reason r ON r.id=f.failure_reason_id
        WHERE f.collection_id=c.id ORDER BY r.code) AS failure_codes FROM public.collection c
        """;
    private static final String FILTER="""
         WHERE (CAST(? AS uuid) IS NULL OR c.establishment_id=?)
         AND (CAST(? AS uuid) IS NULL OR c.driver_id=?)
         AND (CAST(? AS text) IS NULL OR c.result=?::public.collection_result_t)
         AND (CAST(? AS text) IS NULL OR c.record_status=?::public.record_status_t)
         AND (CAST(? AS timestamptz) IS NULL OR c.collection_date>=?)
         AND (CAST(? AS timestamptz) IS NULL OR c.collection_date<?)
        """;
    private final JdbcTemplate jdbc;
    public CollectionReadRepository(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    public Optional<View> find(UUID id,UUID owner) {
        return jdbc.query(SELECT+" WHERE c.id=? AND (CAST(? AS uuid) IS NULL OR c.establishment_id=?)",this::map,id,owner,owner).stream().findFirst();
    }
    public Page list(UUID owner,UUID driver,Query query) {
        String result=query.result()==null ? null : query.result().name();
        String state=query.recordStatus()==null ? null : query.recordStatus().name();
        Timestamp from=timestamp(query.from()),until=timestamp(query.until());
        var args=new ArrayList<Object>(Arrays.asList(owner,owner,driver,driver,result,result,state,state,from,from,until,until));
        long count=Objects.requireNonNull(jdbc.queryForObject("SELECT count(*) FROM public.collection c"+FILTER,Long.class,args.toArray()));
        args.add(query.size());args.add((long)query.page()*query.size());
        var items=jdbc.query(SELECT+FILTER+" ORDER BY c.collection_date DESC,c.id DESC LIMIT ? OFFSET ?",this::map,args.toArray());
        return new Page(items,query.page(),query.size(),count);
    }
    public Optional<Calculation> calculation(UUID id) {
        var current=jdbc.query("""
                SELECT id,revision,points_total,balance_before,balance_after,successful_collections_count
                FROM public.point_calculation WHERE collection_id=? AND is_current
                """,(rs,row)->new Calculation(rs.getObject("id",UUID.class),rs.getInt("revision"),rs.getLong("points_total"),
                    rs.getLong("balance_before"),rs.getLong("balance_after"),rs.getObject("successful_collections_count",Long.class),List.of()),id);
        if(current.isEmpty()) return Optional.empty();
        var value=current.getFirst();
        var components=jdbc.query("SELECT component,points FROM public.point_transaction WHERE point_calculation_id=? ORDER BY component",
                (rs,row)->new PointComponent(rs.getString("component"),rs.getLong("points")),value.id());
        return Optional.of(new Calculation(value.id(),value.revision(),value.nominalPoints(),value.balanceBefore(),value.balanceAfter(),value.successfulCollectionsCount(),components));
    }
    public boolean driverExists(UUID id) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM public.driver WHERE id=?)",Boolean.class,id));
    }
    public Metrics metrics(UUID driver,Period period) {
        return jdbc.queryForObject("""
                SELECT count(*) AS visits,coalesce(sum(collected_volume_liters),0) AS liters,
                count(DISTINCT establishment_id) AS establishments,max(collection_date) AS last_date,
                count(*) FILTER(WHERE result='SUCCESSFUL') AS successful,
                count(*) FILTER(WHERE result='UNSUCCESSFUL') AS unsuccessful
                FROM public.collection WHERE driver_id=? AND record_status='RECORDED'
                AND (CAST(? AS timestamptz) IS NULL OR collection_date>=?)
                AND (CAST(? AS timestamptz) IS NULL OR collection_date<?)
                """,(rs,row)->new Metrics(rs.getLong("visits"),rs.getBigDecimal("liters"),rs.getLong("establishments"),
                    time(rs,"last_date"),rs.getLong("successful"),rs.getLong("unsuccessful")),driver,
                    timestamp(period.from()),timestamp(period.from()),timestamp(period.until()),timestamp(period.until()));
    }
    public Score score(UUID owner) {
        return jdbc.queryForObject("""
                SELECT e.points,(SELECT count(*) FROM public.collection c WHERE c.establishment_id=e.id
                AND c.record_status='RECORDED' AND c.result='SUCCESSFUL') AS successes,
                (SELECT coalesce(sum(c.collected_volume_liters),0) FROM public.collection c
                WHERE c.establishment_id=e.id AND c.record_status='RECORDED') AS liters
                FROM public.establishment e WHERE e.id=?
                """,(rs,row)->new Score(rs.getLong("points"),rs.getLong("successes"),rs.getBigDecimal("liters")),owner);
    }
    private View map(ResultSet rs,int row) throws SQLException {
        var array=rs.getArray("failure_codes");List<String> codes;
        try { codes=List.of((String[])array.getArray()); } finally { array.free(); }
        return new View(rs.getObject("id",UUID.class),rs.getObject("collection_request_id",UUID.class),
                rs.getObject("establishment_id",UUID.class),rs.getObject("driver_id",UUID.class),
                rs.getBigDecimal("collected_volume_liters"),rs.getBigDecimal("presented_volume_liters"),
                OilCondition.valueOf(rs.getString("oil_condition")),rs.getBoolean("has_compromising_occurrence"),
                CollectionResult.valueOf(rs.getString("result")),rs.getLong("points_earned"),rs.getString("observation"),
                time(rs,"collection_date"),time(rs,"created_at"),rs.getLong("processing_order"),
                RecordStatus.valueOf(rs.getString("record_status")),rs.getInt("revision"),codes);
    }
    private static Instant time(ResultSet rs,String field) throws SQLException {
        Timestamp value=rs.getTimestamp(field);return value==null ? null : value.toInstant();
    }
    private static Timestamp timestamp(Instant value) { return value==null ? null : Timestamp.from(value); }
}
