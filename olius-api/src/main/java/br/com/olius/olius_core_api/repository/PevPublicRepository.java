package br.com.olius.olius_core_api.repository;

import br.com.olius.olius_core_api.dto.PevResponses.PublicAddress;
import br.com.olius.olius_core_api.dto.PevResponses.PublicPage;
import br.com.olius.olius_core_api.dto.PevResponses.PublicPev;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Projeção pública própria: não carrega complemento nem nome do responsável cidadão. */
@Repository
public class PevPublicRepository {
    private final JdbcTemplate jdbc;
    public PevPublicRepository(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    /** Pagina somente pontos aprovados, sem carregar complemento ou identidade do responsável cidadão. */
    public PublicPage approved(int page,int size) {
        Long total=jdbc.queryForObject("SELECT count(*) FROM public.pev WHERE status='APPROVED'",Long.class);
        var items=jdbc.query("""
            SELECT p.id,u.name,a.id AS address_id,a.state,a.city,a.neighborhood,a.street,a.number,a.cep,a.latitude,a.longitude
            FROM public.pev p JOIN public.addresses a ON a.id=p.address_id
            LEFT JOIN public.users u ON u.id=p.establishment_id
            WHERE p.status='APPROVED' ORDER BY p.created_at ASC,p.id ASC LIMIT ? OFFSET ?
            """,(rs,row)->new PublicPev(rs.getObject("id",UUID.class),rs.getString("name"),
                new PublicAddress(rs.getObject("address_id",UUID.class),rs.getString("state"),rs.getString("city"),
                    rs.getString("neighborhood"),rs.getString("street"),rs.getString("number"),rs.getString("cep"),
                    rs.getBigDecimal("latitude"),rs.getBigDecimal("longitude"))),size,(long)page*size);
        return new PublicPage(items,page,size,total==null ? 0 : total);
    }
}
