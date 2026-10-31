package br.com.olius.olius_core_api.repository;

import br.com.olius.olius_core_api.dto.DeliveryAdministrationContracts.Detail;
import br.com.olius.olius_core_api.dto.DeliveryCorrectionContracts.Correction;
import br.com.olius.olius_core_api.exception.ResourceNotFoundException;
import java.sql.Timestamp;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class DeliveryCorrectionRepository {
    private final JdbcTemplate jdbc;
    private final DeliveryAdministrationRepository deliveries;
    private final DeliveryCorrectionErrors errors;

    public DeliveryCorrectionRepository(JdbcTemplate jdbc, DeliveryAdministrationRepository deliveries,
            DeliveryCorrectionErrors errors) {
        this.jdbc=jdbc;
        this.deliveries=deliveries;
        this.errors=errors;
    }

    /** Deve ser chamado na transação que configurou o contexto de auditoria. */
    public Detail applyAndRead(UUID id, UUID admin, Correction input) {
        try {
            jdbc.update("""
                CALL public.correct_pev_delivery(?,?,?,?::public.record_status_t,?,?,?)
                """,id,input.expectedRevision(),admin,input.recordStatus().name(),
                input.oilVolumeLiters(),input.deliveryDate()==null ? null : Timestamp.from(input.deliveryDate()),
                input.reason().trim());
        } catch(DataAccessException failure) {
            throw errors.translate(failure);
        }
        return deliveries.detail(id).orElseThrow(ResourceNotFoundException::new);
    }
}
