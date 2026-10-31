package br.com.olius.olius_core_api.repository;

import br.com.olius.olius_core_api.dto.CollectionCorrectionContracts.*;
import br.com.olius.olius_core_api.dto.CollectionContracts.PointComponent;
import br.com.olius.olius_core_api.entity.enums.RecordStatus;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class CollectionCorrectionRepository {
    private final JdbcTemplate jdbc;
    public CollectionCorrectionRepository(JdbcTemplate jdbc) { this.jdbc=jdbc; }

    // Mesma ordem da procedure/registro: estabelecimento antes da coleta.
    public void lockEstablishment(UUID establishment) {
        jdbc.queryForObject("SELECT id FROM public.establishment WHERE id=? FOR UPDATE",UUID.class,establishment);
    }

    public void apply(UUID id,UUID admin,int revision,String justification,RecordStatus status,Correction facts) {
        jdbc.execute((Connection connection)-> {
            var reasons=connection.createArrayOf("text",facts==null ? new String[0] : facts.failureReasonCodes().toArray(String[]::new));
            try(var statement=connection.prepareStatement("CALL public.correct_collection(?,?,?,?::public.record_status_t,?,?,?::public.oil_condition_t,?,?,?,?)")) {
                statement.setObject(1,id);statement.setInt(2,revision);statement.setObject(3,admin);
                statement.setString(4,status.name());
                statement.setBigDecimal(5,facts==null ? null : facts.collectedVolumeLiters());
                statement.setBigDecimal(6,facts==null ? null : facts.presentedVolumeLiters());
                statement.setString(7,facts==null ? null : facts.oilCondition().name());
                statement.setObject(8,facts==null ? null : facts.hasCompromisingOccurrence());
                statement.setArray(9,reasons);statement.setString(10,facts==null ? null : facts.observation());
                statement.setString(11,justification);statement.execute();return null;
            } finally { reasons.free(); }
        });
    }

    public History history(UUID id,HistoryQuery query) {
        long total=Objects.requireNonNull(jdbc.queryForObject("SELECT count(*) FROM public.point_calculation WHERE collection_id=?",Long.class,id));
        var items=jdbc.query("""
                SELECT id,revision,is_current,points_total,balance_before,balance_after,successful_collections_count,
                calculated_at,recalculated_by,recalculation_reason FROM public.point_calculation
                WHERE collection_id=? ORDER BY revision DESC,id DESC LIMIT ? OFFSET ?
                """,this::map,id,query.size(),(long)query.page()*query.size());
        if(items.isEmpty()) return new History(items,query.page(),query.size(),total);
        var components=new HashMap<UUID,List<PointComponent>>();
        jdbc.query("""
                SELECT point_calculation_id,component,points FROM public.point_transaction
                WHERE point_calculation_id IN (
                    SELECT id FROM public.point_calculation WHERE collection_id=?
                    ORDER BY revision DESC,id DESC LIMIT ? OFFSET ?)
                ORDER BY point_calculation_id,component
                """,(org.springframework.jdbc.core.RowCallbackHandler)rs->
                    components.computeIfAbsent(rs.getObject("point_calculation_id",UUID.class),key->new ArrayList<>())
                        .add(new PointComponent(rs.getString("component"),rs.getLong("points"))),
                id,query.size(),(long)query.page()*query.size());
        var detailed=items.stream().map(value->new CalculationRevision(value.id(),value.revision(),value.current(),
                value.nominalPoints(),value.balanceBefore(),value.balanceAfter(),value.successfulCollectionsCount(),
                value.calculatedAt(),value.recalculatedBy(),value.recalculationReason(),components.getOrDefault(value.id(),List.of()))).toList();
        return new History(detailed,query.page(),query.size(),total);
    }

    private CalculationRevision map(ResultSet rs,int row) throws SQLException {
        return new CalculationRevision(rs.getObject("id",UUID.class),rs.getInt("revision"),rs.getBoolean("is_current"),
                rs.getLong("points_total"),rs.getLong("balance_before"),rs.getLong("balance_after"),
                rs.getObject("successful_collections_count",Long.class),rs.getTimestamp("calculated_at").toInstant(),
                rs.getObject("recalculated_by",UUID.class),rs.getString("recalculation_reason"),List.of());
    }
}
