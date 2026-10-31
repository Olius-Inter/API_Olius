package br.com.olius.olius_core_api.repository;

import br.com.olius.olius_core_api.dto.CertificateContracts.*;
import br.com.olius.olius_core_api.entity.enums.CertificateStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class CertificateReadRepository {
    private static final String SELECT="""
        SELECT c.*,l.name AS level_name,l.description AS level_description,l.required_liters,l.badge_image_url
        FROM public.certificate c JOIN public.certificate_level l ON l.id=c.certificate_level_id
        """;
    private static final String FILTER="""
         WHERE (CAST(? AS uuid) IS NULL OR c.establishment_id=?)
         AND (CAST(? AS text) IS NULL OR c.status=?::public.certificate_status_t)
         AND (CAST(? AS uuid) IS NULL OR c.certificate_level_id=?)
         AND (CAST(? AS timestamptz) IS NULL OR c.issued_at>=?)
         AND (CAST(? AS timestamptz) IS NULL OR c.issued_at<?)
        """;
    private final JdbcTemplate jdbc;
    public CertificateReadRepository(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    public List<Level> levels() {
        return jdbc.query("SELECT * FROM public.certificate_level ORDER BY required_liters,name,id",
                (rs,row)->new Level(rs.getObject("id",UUID.class),rs.getString("name"),rs.getString("description"),
                    rs.getBigDecimal("required_liters"),rs.getString("badge_image_url")));
    }
    public boolean establishmentExists(UUID id) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM public.establishment WHERE id=?)",Boolean.class,id));
    }
    public Optional<View> find(UUID id,UUID owner) {
        return jdbc.query(SELECT+" WHERE c.id=? AND (CAST(? AS uuid) IS NULL OR c.establishment_id=?)",this::map,id,owner,owner).stream().findFirst();
    }
    public Page list(UUID owner,Query query) {
        String state=query.status()==null ? null : query.status().name();
        Timestamp from=timestamp(query.issuedFrom()),until=timestamp(query.issuedUntil());
        var args=new ArrayList<Object>(Arrays.asList(owner,owner,state,state,query.levelId(),query.levelId(),from,from,until,until));
        long count=Objects.requireNonNull(jdbc.queryForObject("SELECT count(*) FROM public.certificate c"+FILTER,Long.class,args.toArray()));
        args.add(query.size());args.add((long)query.page()*query.size());
        var items=jdbc.query(SELECT+FILTER+" ORDER BY c.issued_at DESC,c.id DESC LIMIT ? OFFSET ?",this::map,args.toArray());
        return new Page(items,query.page(),query.size(),count);
    }
    public Progress progress(UUID owner) {
        var rows=jdbc.query("SELECT * FROM public.get_certificate_progress(?)",(rs,row)->new ProgressRow(
                rs.getBigDecimal("eligible_volume_liters"),new LevelProgress(
                    rs.getObject("certificate_level_id",UUID.class),rs.getString("level_name"),rs.getString("level_description"),
                    rs.getBigDecimal("required_liters"),rs.getBoolean("is_level_reached"),rs.getBigDecimal("remaining_liters"),
                    rs.getObject("certificate_id",UUID.class),status(rs.getString("certificate_status")))),owner);
        if(!rows.isEmpty()) return new Progress(owner,rows.getFirst().volume(),rows.stream().map(ProgressRow::level).toList());
        // A função não retorna linhas sem catálogo; preservar a mesma base ambiental nesse caso.
        var volume=jdbc.queryForObject("SELECT COALESCE(SUM(collected_volume_liters),0) FROM public.collection WHERE establishment_id=? AND record_status='RECORDED'",
                java.math.BigDecimal.class,owner);
        return new Progress(owner,Objects.requireNonNull(volume),List.of());
    }
    private record ProgressRow(java.math.BigDecimal volume,LevelProgress level) { }
    private View map(ResultSet rs,int row) throws SQLException {
        return new View(rs.getObject("id",UUID.class),rs.getString("certificate_code"),rs.getObject("establishment_id",UUID.class),
                new Level(rs.getObject("certificate_level_id",UUID.class),rs.getString("level_name"),rs.getString("level_description"),
                    rs.getBigDecimal("required_liters"),rs.getString("badge_image_url")),status(rs.getString("status")),
                instant(rs,"issued_at"),instant(rs,"created_at"),instant(rs,"updated_at"),instant(rs,"revoked_at"),instant(rs,"reactivated_at"),rs.getString("status_reason"));
    }
    private static CertificateStatus status(String value) { return value==null ? null : CertificateStatus.valueOf(value); }
    private static Instant instant(ResultSet rs,String column) throws SQLException { var value=rs.getTimestamp(column);return value==null ? null : value.toInstant(); }
    private static Timestamp timestamp(Instant value) { return value==null ? null : Timestamp.from(value); }
}
