package tech.wenisch.contextcrate.repository;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import tech.wenisch.contextcrate.domain.Crate;

public interface CrateRepository extends JpaRepository<Crate, UUID> {}
