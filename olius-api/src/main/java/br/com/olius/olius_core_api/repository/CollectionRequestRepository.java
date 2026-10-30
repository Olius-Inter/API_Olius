package br.com.olius.olius_core_api.repository;

import br.com.olius.olius_core_api.dto.CollectionRequestContracts.*;
import br.com.olius.olius_core_api.entity.enums.CollectionRequestStatus;
import br.com.olius.olius_core_api.entity.enums.UserType;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** A escrita usa exclusivamente os entrypoints oficiais; consultas não expõem logs de auditoria. */
@Repository
public class CollectionRequestRepository {
    private static final String COLUMNS="""
        cr.id,cr.establishment_id,cr.subscription_cycle_id,cr.status,cr.estimated_volume_liters,
        cr.observation,cr.request_at,cr.updated_at,cr.scheduled_at,cr.approved_by,cr.approved_at,
        cr.arrived_at,cr.arrival_recorded_at,cr.arrival_driver_id,
        cr.service_accepted_at,cr.service_acceptance_recorded_at,
        cr.cancelled_at,cr.cancelled_by,cr.cancellation_initiative,cr.cancellation_policy,
        cr.cancellation_reason,cr.forfeited_volume_liters,cr.forfeited_collection_slots,c.id AS collection_id
        """;
    private static final String SOURCE=" FROM public.collection_request cr LEFT JOIN public.collection c ON c.collection_request_id=cr.id ";
    private static final String FILTER="""
         WHERE (CAST(? AS uuid) IS NULL OR cr.establishment_id=?)
           AND (CAST(? AS text) IS NULL OR cr.status=?::public.request_status_t)
           AND (CAST(? AS uuid) IS NULL OR cr.subscription_cycle_id=?)
           AND (CAST(? AS timestamptz) IS NULL OR cr.request_at>=?)
           AND (CAST(? AS timestamptz) IS NULL OR cr.request_at<?)
        """;
    private static final String COUNT_QUERY="SELECT count(*)"+SOURCE+FILTER;
    private static final String PAGE_QUERY="SELECT "+COLUMNS+SOURCE+FILTER+" ORDER BY cr.request_at DESC,cr.id DESC LIMIT ? OFFSET ?";
    private static final String DETAIL_QUERY="SELECT "+COLUMNS+SOURCE+" WHERE cr.id=? AND (CAST(? AS uuid) IS NULL OR cr.establishment_id=?)";
    private static final String ACTOR_QUERY="SELECT id FROM public.users WHERE id=? AND user_type=?::public.user_type_t AND status='ACTIVE'";
    private static final String LOCK_ACTOR_QUERY=ACTOR_QUERY+" FOR SHARE";
    private final JdbcTemplate jdbc;

    public CollectionRequestRepository(JdbcTemplate jdbc) { this.jdbc=jdbc; }

    public boolean activeActor(UUID actor,UserType type,boolean lock) {
        return !jdbc.query(lock ? LOCK_ACTOR_QUERY : ACTOR_QUERY,(rs,row)->rs.getObject(1,UUID.class),actor,type.name()).isEmpty();
    }
    public Optional<RequestView> find(UUID id,UUID owner) {
        return jdbc.query(DETAIL_QUERY,this::map,id,owner,owner).stream().findFirst();
    }
    public Page list(UUID owner,Query query) {
        String state=query.status()==null ? null : query.status().name();
        Timestamp from=timestamp(query.from()),until=timestamp(query.until());
        Long count=jdbc.queryForObject(COUNT_QUERY,Long.class,owner,owner,state,state,
                query.cycleId(),query.cycleId(),from,from,until,until);
        List<RequestView> items=jdbc.query(PAGE_QUERY,this::map,owner,owner,state,state,
                query.cycleId(),query.cycleId(),from,from,until,until,query.size(),(long)query.page()*query.size());
        return new Page(items,query.page(),query.size(),count==null ? 0 : count);
    }
    public SchedulePage history(UUID id,int page,int size) {
        Long count=jdbc.queryForObject("SELECT count(*) FROM public.collection_schedule_history WHERE collection_request_id=?",Long.class,id);
        var items=jdbc.query("""
                SELECT id,previous_scheduled_at,new_scheduled_at,agreed_at,changed_at,changed_by,reason
                FROM public.collection_schedule_history WHERE collection_request_id=?
                ORDER BY changed_at DESC,id DESC LIMIT ? OFFSET ?
                """,(rs,row)->new ScheduleChange(rs.getObject("id",UUID.class),time(rs,"previous_scheduled_at"),
                    time(rs,"new_scheduled_at"),time(rs,"agreed_at"),time(rs,"changed_at"),
                    rs.getObject("changed_by",UUID.class),rs.getString("reason")),id,size,(long)page*size);
        return new SchedulePage(items,page,size,count==null ? 0 : count);
    }
    public Optional<UUID> currentCycle(UUID owner) {
        return jdbc.query("""
                SELECT id FROM public.subscription_cycle WHERE establishment_id=?
                AND CURRENT_TIMESTAMP>=starts_at AND CURRENT_TIMESTAMP<ends_at
                """,(rs,row)->rs.getObject("id",UUID.class),owner).stream().findFirst();
    }
    public Optional<Availability> availability(UUID cycle,UUID owner) {
        if(!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM public.subscription_cycle WHERE id=? AND establishment_id=?)",Boolean.class,cycle,owner)))
            return Optional.empty();
        return jdbc.query("SELECT * FROM public.get_cycle_availability(?)",(rs,row)->new Availability(
                rs.getObject("cycle_id",UUID.class),rs.getObject("establishment_id",UUID.class),
                rs.getBigDecimal("volume_limit_liters"),rs.getInt("collection_limit"),
                rs.getBigDecimal("reserved_volume_liters"),rs.getLong("reserved_collection_slots"),
                rs.getBigDecimal("consumed_volume_liters"),rs.getLong("consumed_collection_slots"),
                rs.getBigDecimal("forfeited_volume_liters"),rs.getLong("forfeited_collection_slots"),
                rs.getBigDecimal("available_volume_liters"),rs.getLong("available_collection_slots"),
                rs.getBoolean("is_subscription_active"),rs.getBoolean("is_cycle_current"),rs.getBoolean("can_accept_new_request")),cycle)
                .stream().findFirst();
    }
    public UUID create(UUID actor,Create request,UUID key) {
        return jdbc.queryForObject("CALL public.create_collection_request(?,?,?,?,NULL)",UUID.class,
                actor,request.estimatedVolumeLiters(),request.observation(),key);
    }
    public void schedule(UUID id,UUID actor,Scheduling request) {
        jdbc.update("CALL public.schedule_collection_request(?,?,?,?,?)",id,timestamp(request.scheduledAt()),
                timestamp(request.agreedAt()),actor,request.reason());
    }
    public void reject(UUID id,UUID actor,String reason) {
        jdbc.queryForObject("SELECT public.reject_collection_request(?,?,?)",Object.class,id,actor,reason);
    }
    public void cancel(UUID id,UUID actor,boolean administrative,String reason) {
        jdbc.update("CALL public.cancel_collection_request(?,?,?::public.cancellation_initiative_t,?)",
                id,actor,administrative ? "OPERATION" : "ESTABLISHMENT",reason);
    }
    private RequestView map(ResultSet rs,int row) throws SQLException {
        var approved=rs.getObject("approved_by",UUID.class);
        var arrival=time(rs,"arrived_at");
        var accepted=time(rs,"service_accepted_at");
        var cancelled=time(rs,"cancelled_at");
        return new RequestView(rs.getObject("id",UUID.class),rs.getObject("establishment_id",UUID.class),
                rs.getObject("subscription_cycle_id",UUID.class),CollectionRequestStatus.valueOf(rs.getString("status")),
                rs.getBigDecimal("estimated_volume_liters"),rs.getString("observation"),time(rs,"request_at"),time(rs,"updated_at"),
                time(rs,"scheduled_at"),approved==null ? null : new Approval(approved,time(rs,"approved_at")),
                arrival==null ? null : new Arrival(arrival,time(rs,"arrival_recorded_at"),rs.getObject("arrival_driver_id",UUID.class)),
                accepted==null ? null : new Acceptance(accepted,time(rs,"service_acceptance_recorded_at")),
                cancelled==null ? null : new Cancellation(cancelled,rs.getObject("cancelled_by",UUID.class),
                    rs.getString("cancellation_initiative"),rs.getString("cancellation_policy"),rs.getString("cancellation_reason"),
                    rs.getBigDecimal("forfeited_volume_liters"),rs.getInt("forfeited_collection_slots")),
                rs.getObject("collection_id",UUID.class));
    }
    private static Instant time(ResultSet rs,String field) throws SQLException {
        Timestamp value=rs.getTimestamp(field); return value==null ? null : value.toInstant();
    }
    private static Timestamp timestamp(Instant value) { return value==null ? null : Timestamp.from(value); }
}
