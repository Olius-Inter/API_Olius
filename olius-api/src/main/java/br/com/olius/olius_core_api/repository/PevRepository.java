package br.com.olius.olius_core_api.repository;

import br.com.olius.olius_core_api.entity.Pev;
import br.com.olius.olius_core_api.entity.enums.PevStatus;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PevRepository extends JpaRepository<Pev, UUID> {
    @EntityGraph(attributePaths="address")
    Optional<Pev> findByCitizenId(UUID citizenId);

    @EntityGraph(attributePaths="address")
    Optional<Pev> findByEstablishmentId(UUID establishmentId);

    @EntityGraph(attributePaths="address")
    Page<Pev> findByStatus(PevStatus status, Pageable pageable);
}
