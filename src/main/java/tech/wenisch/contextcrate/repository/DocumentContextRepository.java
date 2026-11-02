package tech.wenisch.contextcrate.repository;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import tech.wenisch.contextcrate.domain.DocumentContext;

public interface DocumentContextRepository extends JpaRepository<DocumentContext, UUID> {
  Optional<DocumentContext> findByDocumentId(UUID documentId);
}
