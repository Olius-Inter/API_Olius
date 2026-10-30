package br.com.olius.olius_core_api.repository;

import br.com.olius.olius_core_api.dto.CollectionContracts.*;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class CollectionOperationsRepository {
    public record Identity(UUID driverId,String driverName,UUID establishmentId,String establishmentName) { }
    private static final String CONTEXT_SOURCE="""
                FROM public.collection_request r WHERE r.establishment_id=? AND r.status='APPROVED'
                AND r.scheduled_at IS NOT NULL AND NOT EXISTS(SELECT 1 FROM public.collection c WHERE c.collection_request_id=r.id)
                """;
    private static final String CONTEXT_COUNT="SELECT count(*) "+CONTEXT_SOURCE;
    private static final String CONTEXT_PAGE="SELECT r.id,r.estimated_volume_liters,r.scheduled_at,r.arrived_at,r.service_accepted_at "
            +CONTEXT_SOURCE+" ORDER BY r.scheduled_at,r.id LIMIT ? OFFSET ?";
    private final JdbcTemplate jdbc;
    public CollectionOperationsRepository(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    public Optional<Identity> identify(DriverAccess access) {
        // Bloqueia o motorista contra inativação e o QR/usuário contra troca/inativação durante a ação.
        var driver=jdbc.query("SELECT id,name FROM public.driver WHERE cpf=? AND cnh=? AND status='ACTIVE' FOR SHARE",
                (rs,row)->Map.entry(rs.getObject("id",UUID.class),rs.getString("name")),access.cpf(),access.cnh());
        if(driver.isEmpty()) return Optional.empty();
        return jdbc.query("""
                SELECT q.user_id,u.name FROM public.user_qr_code q JOIN public.users u ON u.id=q.user_id
                JOIN public.establishment e ON e.id=u.id AND e.qr_token=q.qr_token
                WHERE q.qr_token=? AND q.user_type='ESTABLISHMENT' AND u.user_type='ESTABLISHMENT' AND u.status='ACTIVE'
                FOR SHARE OF q,u
                """,(rs,row)->new Identity(driver.getFirst().getKey(),driver.getFirst().getValue(),
                    rs.getObject("user_id",UUID.class),rs.getString("name")),access.qrToken()).stream().findFirst();
    }
    public Context context(Identity identity,int page,int size) {
        long count=Objects.requireNonNull(jdbc.queryForObject(CONTEXT_COUNT,Long.class,identity.establishmentId()));
        var requests=jdbc.query(CONTEXT_PAGE,(rs,row)->new OperationalRequest(rs.getObject("id",UUID.class),
                    rs.getBigDecimal("estimated_volume_liters"),time(rs.getTimestamp("scheduled_at")),time(rs.getTimestamp("arrived_at")),
                    time(rs.getTimestamp("service_accepted_at"))),identity.establishmentId(),size,(long)page*size);
        var reasons=jdbc.query("""
                SELECT code,name,description FROM public.collection_failure_reason WHERE status='ACTIVE'
                AND code NOT IN ('ESTABLISHMENT_CLOSED','NO_RESPONSIBLE_AVAILABLE') ORDER BY code
                """,(rs,row)->new FailureReason(rs.getString("code"),rs.getString("name"),rs.getString("description")));
        return new Context(identity.driverId(),identity.driverName(),identity.establishmentId(),identity.establishmentName(),requests,page,size,count,reasons);
    }
    public Instant arrival(UUID request,UUID driver) {
        return time(jdbc.queryForObject("SELECT public.record_collection_arrival(?,?)",Timestamp.class,request,driver));
    }
    public Instant accept(UUID request,UUID establishment) {
        return time(jdbc.queryForObject("SELECT public.accept_collection_service(?,?)",Timestamp.class,request,establishment));
    }
    public UUID registerCollection(UUID request,UUID driver,br.com.olius.olius_core_api.dto.CollectionContracts.Record input) {
        // A mesma solicitação serializa reenvios da Core sem DML ou acesso aos logs.
        jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(?,0))",Object.class,"olius:collection:"+request);
        Timestamp date=jdbc.queryForObject("""
                SELECT COALESCE((SELECT collection_date FROM public.collection WHERE collection_request_id=?),clock_timestamp())
                """,Timestamp.class,request);
        return jdbc.execute((java.sql.Connection connection)-> {
            var reasons=connection.createArrayOf("text",input.failureReasonCodes().toArray(String[]::new));
            try(var statement=connection.prepareStatement("CALL public.record_collection(?,?,?,?,?::public.oil_condition_t,?,?,?,?,NULL)")) {
                statement.setObject(1,request);statement.setObject(2,driver);
                statement.setBigDecimal(3,input.collectedVolumeLiters());statement.setBigDecimal(4,input.presentedVolumeLiters());
                statement.setString(5,input.oilCondition().name());statement.setBoolean(6,input.hasCompromisingOccurrence());
                statement.setArray(7,reasons);statement.setString(8,input.observation());statement.setTimestamp(9,date);
                try(var rows=statement.executeQuery()) { rows.next();return rows.getObject(1,UUID.class); }
            } finally { reasons.free(); }
        });
    }
    public UUID failedVisit(UUID request,UUID admin,FailedVisit input) {
        return jdbc.queryForObject("SELECT public.record_failed_collection_visit(?,?,?,?)",UUID.class,
                request,admin,input.reasonCode().name(),input.justification());
    }
    private static Instant time(Timestamp value) { return value==null ? null : value.toInstant(); }
}
