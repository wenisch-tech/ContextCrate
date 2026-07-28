package tech.wenisch.contextcrate.repository;

import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

public interface NormalizedDocumentRepositoryCustom {
  Page<DocumentListRow> findCurrentPage(UUID crateId, String query, DocumentSort sort,
      Sort.Direction direction, Pageable pageable);
}
