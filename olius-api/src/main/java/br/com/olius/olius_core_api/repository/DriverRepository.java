package br.com.olius.olius_core_api.repository;

import br.com.olius.olius_core_api.entity.Driver;
import br.com.olius.olius_core_api.entity.enums.UserStatus;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;

public interface DriverRepository extends JpaRepository<Driver,UUID> {
    Page<Driver> findByStatus(UserStatus status,Pageable pageable);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from Driver d where d.id=:id")
    Optional<Driver> findForUpdate(UUID id);
}
