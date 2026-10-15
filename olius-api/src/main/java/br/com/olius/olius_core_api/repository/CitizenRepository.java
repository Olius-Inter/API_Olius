package br.com.olius.olius_core_api.repository;
import br.com.olius.olius_core_api.entity.Citizen;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
public interface CitizenRepository extends JpaRepository<Citizen, UUID> {  }
