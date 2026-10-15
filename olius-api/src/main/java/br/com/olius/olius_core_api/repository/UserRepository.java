package br.com.olius.olius_core_api.repository;
import br.com.olius.olius_core_api.entity.User;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
public interface UserRepository extends JpaRepository<User, UUID> { java.util.Optional<User> findByEmail(String email); }
