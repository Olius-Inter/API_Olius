package br.com.olius.olius_core_api.repository;

import br.com.olius.olius_core_api.dto.CollectionCorrectionContracts.HistoryQuery;
import br.com.olius.olius_core_api.dto.CollectionOperationalHistoryContracts.*;
import br.com.olius.olius_core_api.entity.enums.*;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class CollectionOperationalHistoryRepository {
    private final JdbcTemplate jdbc;
    public CollectionOperationalHistoryRepository(JdbcTemplate jdbc) { this.jdbc=jdbc; }

    public OperationalHistory history(UUID id,UUID admin,HistoryQuery query) {
        return jdbc.query("""
                SELECT * FROM public.get_collection_revision_history(?,?,?,?)
                ORDER BY revision DESC NULLS LAST,audit_event_id DESC
                """,(org.springframework.jdbc.core.ResultSetExtractor<OperationalHistory>)rs->{
                    if(!rs.next()) throw new IllegalStateException("Histórico sem metadados de paginação");
                    int page=rs.getInt("page"),size=rs.getInt("size");long total=rs.getLong("total_elements");
                    var items=new ArrayList<OperationalRevision>();
                    do {
                        if(rs.getObject("revision")!=null) items.add(map(rs));
                    } while(rs.next());
                    return new OperationalHistory(items,page,size,total);
                },id,admin,query.page(),query.size());
    }

    private OperationalRevision map(ResultSet rs) throws SQLException {
        return new OperationalRevision(rs.getObject("collection_id",UUID.class),rs.getInt("revision"),
                RecordStatus.valueOf(rs.getString("record_status")),rs.getBigDecimal("collected_volume_liters"),
                rs.getBigDecimal("presented_volume_liters"),OilCondition.valueOf(rs.getString("oil_condition")),
                rs.getBoolean("has_compromising_occurrence"),CollectionResult.valueOf(rs.getString("result")),
                rs.getString("observation"),reasons(rs),rs.getObject("collection_request_id",UUID.class),
                rs.getObject("establishment_id",UUID.class),rs.getObject("driver_id",UUID.class),
                instant(rs,"collection_date"),instant(rs,"recorded_at"),instant(rs,"changed_at"),
                instant(rs,"corrected_at"),rs.getObject("corrected_by",UUID.class),rs.getString("correction_reason"),
                rs.getString("actor_kind"),rs.getObject("performed_by",UUID.class),
                rs.getObject("operational_driver_id",UUID.class),rs.getString("audit_reason"),rs.getObject("audit_event_id",UUID.class));
    }

    private List<HistoricalReason> reasons(ResultSet rs) throws SQLException {
        var ids=rs.getArray("failure_reason_ids");
        try {
            var codes=rs.getArray("failure_reason_codes");
            try {
                var values=(UUID[])ids.getArray();var labels=(String[])codes.getArray();
                if(values.length!=labels.length) throw new IllegalStateException("Motivos históricos desalinhados");
                var result=new ArrayList<HistoricalReason>(values.length);
                for(int i=0;i<values.length;i++) result.add(new HistoricalReason(values[i],labels[i]));
                return result;
            } finally { codes.free(); }
        } finally { ids.free(); }
    }
    private static Instant instant(ResultSet rs,String field) throws SQLException {
        var value=rs.getTimestamp(field);return value==null ? null : value.toInstant();
    }
}
