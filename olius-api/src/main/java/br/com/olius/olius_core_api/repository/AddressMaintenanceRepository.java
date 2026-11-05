package br.com.olius.olius_core_api.repository;

import br.com.olius.olius_core_api.dto.IdentityRequests.AddressInput;
import br.com.olius.olius_core_api.dto.IdentityResponses.AddressView;
import br.com.olius.olius_core_api.entity.Address;
import br.com.olius.olius_core_api.entity.enums.UserType;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AddressMaintenanceRepository {
    private final JdbcTemplate jdbc;
    public AddressMaintenanceRepository(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    /** Confirma tipo e conta ativa sob bloqueio compartilhado, impedindo inativação durante a escrita. */
    public boolean activeActor(UUID actor,UserType role) {
        return !jdbc.query("SELECT id FROM public.users WHERE id=? AND user_type=?::public.user_type_t AND status='ACTIVE' FOR SHARE",
            (rs,row)->rs.getObject(1,UUID.class),actor,role.name()).isEmpty();
    }
    /** Compartilha o bloqueio que a procedure de criação adquire antes de inserir novas solicitações. */
    public Optional<UUID> lockEstablishment(UUID actor) {
        return jdbc.query("SELECT address_id FROM public.establishment WHERE id=? FOR UPDATE",
            (rs,row)->rs.getObject(1,UUID.class),actor).stream().findFirst();
    }
    /** Detecta pedidos ainda sem visita; APPROVED com coleta registrada não bloqueia manutenção futura. */
    public boolean hasUnfinishedRequests(UUID actor) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT EXISTS(SELECT 1 FROM public.collection_request r
                WHERE r.establishment_id=? AND r.status IN ('PENDING','APPROVED')
                AND NOT EXISTS(SELECT 1 FROM public.collection c WHERE c.collection_request_id=r.id))
            """,Boolean.class,actor));
    }
    /** Lê o endereço pelo ID e natureza esperados; o chamador valida previamente a titularidade. */
    public AddressView find(UUID id,Address.OwnerKind kind) {
        return jdbc.queryForObject("SELECT * FROM public.addresses WHERE id=? AND owner_kind=?::public.address_owner_t",
            (rs,row)->new AddressView(rs.getObject("id",UUID.class),rs.getString("state"),rs.getString("city"),
                rs.getString("neighborhood"),rs.getString("street"),rs.getString("number"),rs.getString("cep"),
                rs.getString("complement"),rs.getBigDecimal("latitude"),rs.getBigDecimal("longitude")),id,kind.name());
    }
    /** Substitui campos editáveis sem trocar o ID e falha se o endereço esperado não for atualizado. */
    public void replace(UUID id,Address.OwnerKind kind,AddressInput input) {
        int count=jdbc.update("""
            UPDATE public.addresses SET state=?,city=?,neighborhood=?,street=?,number=?,cep=?,
                complement=?,latitude=?,longitude=? WHERE id=? AND owner_kind=?::public.address_owner_t
            """,input.state(),input.city(),input.neighborhood(),input.street(),input.number(),input.cep(),
            input.complement(),input.latitude(),input.longitude(),id,kind.name());
        if(count!=1) throw new IllegalStateException("Endereço do titular indisponível");
    }
    /** Equivalência numérica das coordenadas evita revisões por simples diferença de escala decimal. */
    public static boolean same(AddressView old,AddressInput next) {
        var previous=new Address.PostalDetails(old.state(),old.city(),old.neighborhood(),old.street(),old.number(),old.cep(),old.complement());
        var requested=new Address.PostalDetails(next.state(),next.city(),next.neighborhood(),next.street(),next.number(),next.cep(),next.complement());
        return previous.equals(requested) && sameNumber(old.latitude(),next.latitude()) && sameNumber(old.longitude(),next.longitude());
    }
    /** Compara valores numéricos ignorando escala; ausência só equivale a outra ausência. */
    private static boolean sameNumber(BigDecimal one,BigDecimal other) {
        if (one == null) return other == null;
        return other != null && one.compareTo(other) == 0;
    }
}
