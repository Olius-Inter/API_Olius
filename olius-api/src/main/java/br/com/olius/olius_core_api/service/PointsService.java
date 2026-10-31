package br.com.olius.olius_core_api.service;

import br.com.olius.olius_core_api.dto.PointsContracts.Movement;
import br.com.olius.olius_core_api.dto.PointsContracts.Summary;
import br.com.olius.olius_core_api.exception.ResourceNotFoundException;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class PointsService {
    private final JdbcTemplate jdbc;
    public PointsService(JdbcTemplate jdbc) { this.jdbc=jdbc; }

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Summary own(UUID citizen,int page,int size) {
        long offset=(long)page*size;
        if(offset>Integer.MAX_VALUE) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        var balances=jdbc.query("SELECT points FROM public.citizens WHERE id=?",
            (rs,row)->rs.getLong("points"),citizen);
        if(balances.isEmpty()) throw new ResourceNotFoundException();
        long count=jdbc.queryForObject("""
            SELECT count(*) FROM public.point_calculation pc
            WHERE pc.user_id=? AND pc.delivery_pev_id IS NOT NULL AND pc.is_current
            """,Long.class,citizen);
        var movements=jdbc.query("""
            SELECT d.id AS delivery_id,pc.points_total,d.delivery_date,pc.revision
            FROM public.point_calculation pc
            JOIN public.delivery_pev d ON d.id=pc.delivery_pev_id
            WHERE pc.user_id=? AND pc.is_current
            ORDER BY d.delivery_date DESC,d.id DESC LIMIT ? OFFSET ?
            """,(rs,row)->new Movement(rs.getObject("delivery_id",UUID.class),
                rs.getLong("points_total"),rs.getTimestamp("delivery_date").toInstant(),
                rs.getInt("revision")),citizen,size,offset);
        return new Summary(balances.getFirst(),movements,page,size,count);
    }
}
