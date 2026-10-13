package br.com.olius.olius_core_api.repository;
import br.com.olius.olius_core_api.entity.Address;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
public interface AddressRepository extends JpaRepository<Address, UUID> {  }
