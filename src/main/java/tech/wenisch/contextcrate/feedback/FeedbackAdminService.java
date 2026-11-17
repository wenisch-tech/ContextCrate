package tech.wenisch.contextcrate.feedback;

import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tech.wenisch.contextcrate.domain.AnswerFeedback;
import tech.wenisch.contextcrate.repository.AnswerFeedbackRepository;
import tech.wenisch.contextcrate.repository.AnswerFeedbackSourceRepository;

/** Owner-side management of collected feedback: list, approve or reject for enrichment, delete. */
@Service
public class FeedbackAdminService {
  private final AnswerFeedbackRepository feedback;
  private final AnswerFeedbackSourceRepository sources;
  private final FeedbackEnrichmentService enrichment;

  public FeedbackAdminService(AnswerFeedbackRepository feedback, AnswerFeedbackSourceRepository sources, FeedbackEnrichmentService enrichment) {
    this.feedback = feedback; this.sources = sources; this.enrichment = enrichment;
  }

  /** Only rated entries are interesting for an owner; unrated ones are just open links. */
  @Transactional(readOnly = true)
  public List<AnswerFeedback> recent(UUID crateId, int limit) {
    return feedback.findByCrateIdOrderByCreatedAtDesc(crateId, PageRequest.of(0, Math.max(1, Math.min(limit, 500)))).stream().filter(f -> f.getRatedAt() != null).toList();
  }

  @Transactional
  public void approve(UUID crateId, UUID id) {
    var f = feedback.findByIdAndCrateId(id, crateId).orElseThrow();
    if (f.getRelevance() == null && f.getQuality() == null) throw new IllegalStateException("not rated");
    if (f.getEnrichmentStatus() != AnswerFeedback.Enrichment.INDEXED) { f.enrichment(AnswerFeedback.Enrichment.APPROVED); feedback.save(f); }
  }

  @Transactional
  public void reject(UUID crateId, UUID id) {
    var f = feedback.findByIdAndCrateId(id, crateId).orElseThrow();
    enrichment.removeDocument(f);
    f.enrichment(AnswerFeedback.Enrichment.REJECTED);
    feedback.save(f);
  }

  @Transactional
  public void delete(UUID crateId, UUID id) {
    var f = feedback.findByIdAndCrateId(id, crateId).orElseThrow();
    enrichment.removeDocument(f);
    sources.deleteByFeedbackId(id);
    feedback.delete(f);
  }
}
