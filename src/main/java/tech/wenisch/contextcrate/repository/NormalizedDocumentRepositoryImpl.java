package tech.wenisch.contextcrate.repository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Repository;
import tech.wenisch.contextcrate.domain.DocumentChunk;
import tech.wenisch.contextcrate.domain.NormalizedDocument;

@Repository
class NormalizedDocumentRepositoryImpl implements NormalizedDocumentRepositoryCustom {
  @PersistenceContext private EntityManager entityManager;

  @Override
  public Page<DocumentListRow> findCurrentPage(UUID crateId, String query, DocumentSort sort,
      Sort.Direction direction, Pageable pageable) {
    CriteriaBuilder cb = entityManager.getCriteriaBuilder();
    CriteriaQuery<NormalizedDocument> contentQuery = cb.createQuery(NormalizedDocument.class);
    Root<NormalizedDocument> document = contentQuery.from(NormalizedDocument.class);
    Predicate filter = filter(cb, document, crateId, query);
    contentQuery.where(filter);
    Expression<?> order = order(cb, contentQuery, document, sort);
    contentQuery.orderBy(direction.isAscending() ? cb.asc(order) : cb.desc(order), cb.asc(document.get("id")));
    List<NormalizedDocument> documents = entityManager.createQuery(contentQuery)
        .setFirstResult((int) pageable.getOffset()).setMaxResults(pageable.getPageSize()).getResultList();

    CriteriaQuery<Long> countQuery = cb.createQuery(Long.class);
    Root<NormalizedDocument> counted = countQuery.from(NormalizedDocument.class);
    countQuery.select(cb.count(counted)).where(filter(cb, counted, crateId, query));
    long total = entityManager.createQuery(countQuery).getSingleResult();
    var ids = documents.stream().map(NormalizedDocument::getId).toList();
    var counts = ids.isEmpty() ? java.util.Map.<UUID, Long>of()
        : entityManager.createQuery("select c.documentId, count(c) from DocumentChunk c "
            + "where c.documentId in :ids group by c.documentId", Object[].class)
            .setParameter("ids", ids).getResultList().stream().collect(java.util.stream.Collectors.toMap(
                row -> (UUID) row[0], row -> (Long) row[1]));
    List<DocumentListRow> rows = new ArrayList<>();
    for (NormalizedDocument value : documents)
      rows.add(new DocumentListRow(value, counts.getOrDefault(value.getId(), 0L)));
    return new PageImpl<>(rows, pageable, total);
  }

  private static Predicate filter(CriteriaBuilder cb, Root<NormalizedDocument> document,
      UUID crateId, String query) {
    List<Predicate> predicates = new ArrayList<>();
    predicates.add(cb.equal(document.get("crateId"), crateId));
    predicates.add(cb.isTrue(document.get("currentVersion")));
    if (query != null && !query.isBlank()) {
      String pattern = "%" + query.trim().toLowerCase(java.util.Locale.ROOT) + "%";
      predicates.add(cb.or(
          cb.like(cb.lower(cb.coalesce(document.get("title"), "")), pattern),
          cb.like(cb.lower(document.get("sourceUri")), pattern)));
    }
    return cb.and(predicates.toArray(Predicate[]::new));
  }

  private static Expression<?> order(CriteriaBuilder cb, CriteriaQuery<?> query,
      Root<NormalizedDocument> document, DocumentSort sort) {
    return switch (sort) {
      case TITLE -> cb.lower(cb.coalesce(document.get("title"), ""));
      case URI -> cb.lower(document.get("sourceUri"));
      case INDEXED -> document.get("indexed");
      case CHUNKS -> chunkCount(cb, query, document);
      case CREATED -> document.get("createdAt");
    };
  }

  private static Expression<Long> chunkCount(CriteriaBuilder cb, CriteriaQuery<?> query,
      Root<NormalizedDocument> document) {
    Subquery<Long> count = query.subquery(Long.class);
    Root<DocumentChunk> chunk = count.from(DocumentChunk.class);
    count.select(cb.count(chunk)).where(cb.equal(chunk.get("documentId"), document.get("id")));
    return count;
  }
}
