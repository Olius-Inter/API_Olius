package br.com.olius.olius_core_api.repository;
import br.com.olius.olius_core_api.entity.Establishment;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
public interface EstablishmentRepository extends JpaRepository<Establishment, UUID> {  }
