package br.com.olius.olius_core_api.service;

import br.com.olius.olius_core_api.entity.enums.PevStatus;
import br.com.olius.olius_core_api.entity.enums.UserStatus;
import br.com.olius.olius_core_api.entity.enums.UserType;
import br.com.olius.olius_core_api.exception.ResourceNotFoundException;
import br.com.olius.olius_core_api.repository.PevRepository;
import br.com.olius.olius_core_api.repository.UserRepository;
import br.com.olius.olius_core_api.security.SecretTokens;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class QrIdentificationService {
    private static final String FIND_CITIZEN = """
        SELECT u.id, u.name
        FROM user_qr_code q
        JOIN users u ON u.id = q.user_id
        WHERE q.qr_token = ? AND q.user_type = 'CITIZENS'
          AND u.user_type = 'CITIZENS' AND u.status = 'ACTIVE'
        """;

    private final PevRepository pevs;
    private final UserRepository users;
    private final JdbcTemplate jdbc;
    private final SecretTokens tokens;

    public QrIdentificationService(PevRepository pevs, UserRepository users,
                                   JdbcTemplate jdbc, SecretTokens tokens) {
        this.pevs = pevs;
        this.users = users;
        this.jdbc = jdbc;
        this.tokens = tokens;
    }

    @Transactional(readOnly = true)
    public IdentifiedCitizen identify(String qrToken, UUID actor, UserType actorType) {
        if (actorType != UserType.CITIZENS && actorType != UserType.ESTABLISHMENT)
            throw new AccessDeniedException("Perfil sem vínculo de PEV");
        var validator = users.findById(actor)
            .orElseThrow(() -> new AccessDeniedException("Responsável indisponível"));
        if (validator.getStatus() != UserStatus.ACTIVE || validator.getUserType() != actorType)
            throw new AccessDeniedException("Responsável indisponível");
        var pev = (actorType == UserType.CITIZENS
            ? pevs.findByCitizenId(actor) : pevs.findByEstablishmentId(actor))
            .orElseThrow(() -> new AccessDeniedException("PEV indisponível"));
        if (pev.getStatus() != PevStatus.APPROVED)
            throw new AccessDeniedException("PEV indisponível");
        if (!tokens.valid(qrToken)) throw new ResourceNotFoundException();

        var citizens = jdbc.query(FIND_CITIZEN,
            (rs, row) -> new IdentifiedCitizen(rs.getObject("id", UUID.class), rs.getString("name")),
            qrToken);
        var citizen = citizens.stream().findFirst().orElseThrow(ResourceNotFoundException::new);
        if (citizen.id().equals(actor)) throw new AccessDeniedException("Autoentrega proibida");
        return citizen;
    }

    public record IdentifiedCitizen(UUID id, String name) { }
}
