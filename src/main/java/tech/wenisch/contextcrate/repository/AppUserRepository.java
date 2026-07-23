package tech.wenisch.contextcrate.repository;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import tech.wenisch.contextcrate.domain.AppUser;

public interface AppUserRepository extends JpaRepository<AppUser, UUID> {
  Optional<AppUser> findByEmailIgnoreCase(String email);
}
