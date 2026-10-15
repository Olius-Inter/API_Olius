package br.com.olius.olius_core_api.repository;
import br.com.olius.olius_core_api.entity.Telephone;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
public interface TelephoneRepository extends JpaRepository<Telephone, UUID> {
    List<Telephone> findByUserIdOrderByNumber(UUID userId);
}
