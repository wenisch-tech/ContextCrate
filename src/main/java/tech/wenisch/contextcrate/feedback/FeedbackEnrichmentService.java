package tech.wenisch.contextcrate.feedback;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tech.wenisch.contextcrate.domain.*;
import tech.wenisch.contextcrate.domain.PipelineTypes.FetchOutcome;
import tech.wenisch.contextcrate.domain.PipelineTypes.FrontierStatus;
import tech.wenisch.contextcrate.domain.PipelineTypes.RunStatus;
import tech.wenisch.contextcrate.domain.PipelineTypes.WorkStage;
import tech.wenisch.contextcrate.index.SearchIndex;
import tech.wenisch.contextcrate.queue.PipelineMessage;
import tech.wenisch.contextcrate.queue.PipelineQueue;
import tech.wenisch.contextcrate.repository.*;
import tech.wenisch.contextcrate.service.IngestionService;
import tech.wenisch.contextcrate.storage.Hashing;

/**
 * Turns well rated answers into synthetic documents: the question and its verified answer are stored as one
 * document of the crate's internal feedback source and indexed like any other document.
 */
@Service
public class FeedbackEnrichmentService {
  private static final Logger log = LoggerFactory.getLogger(FeedbackEnrichmentService.class);
  static final int MAX_ANSWER_CHARACTERS = 8_000;
  private static final Pattern CITATION = Pattern.compile("[ \\t]*\\[(?:SOURCE\\s+)?\\d+\\]");

  private final AnswerFeedbackRepository feedback;
  private final AnswerFeedbackSourceRepository feedbackSources;
  private final SourceRepository sources;
  private final IngestionJobRepository jobs;
  private final IngestionRunRepository runs;
  private final SourceItemRepository items;
  private final AcquisitionRecordRepository acquisitions;
  private final NormalizedDocumentRepository documents;
  private final DocumentChunkRepository chunks;
  private final PipelineQueue queue;
  private final SearchIndex index;

  public FeedbackEnrichmentService(AnswerFeedbackRepository feedback, AnswerFeedbackSourceRepository feedbackSources, SourceRepository sources,
      IngestionJobRepository jobs, IngestionRunRepository runs, SourceItemRepository items, AcquisitionRecordRepository acquisitions,
      NormalizedDocumentRepository documents, DocumentChunkRepository chunks, PipelineQueue queue, SearchIndex index) {
    this.feedback = feedback; this.feedbackSources = feedbackSources; this.sources = sources; this.jobs = jobs; this.runs = runs;
    this.items = items; this.acquisitions = acquisitions; this.documents = documents; this.chunks = chunks; this.queue = queue; this.index = index;
  }

  /** Indexes every approved entry of the crate (the ones waiting for the owner are skipped). @return the number of documents created */
  @Transactional
  public int enrich(UUID crateId) {
    var ready = feedback.findByCrateIdAndEnrichmentStatus(crateId, AnswerFeedback.Enrichment.APPROVED);
    if (ready.isEmpty()) return 0;
    Source source = feedbackSource(crateId);
    IngestionJob job = jobs.findBySourceIdOrderByCreatedAtDesc(source.getId()).stream().findFirst()
        .orElseGet(() -> jobs.save(new IngestionJob(UUID.randomUUID(), crateId, source.getId(), "Feedback", "{}")));
    IngestionRun run = new IngestionRun(UUID.randomUUID(), crateId, source.getId(), job.getId(), "{}", "{}");
    run = runs.save(run);
    int created = 0;
    for (var f : ready) {
      try { create(run, f); created++; } catch (RuntimeException e) { log.warn("Could not index feedback {}: {}", f.getId(), e.toString()); }
    }
    run.status(RunStatus.COMPLETED);
    runs.save(run);
    return created;
  }

  private void create(IngestionRun run, AnswerFeedback f) {
    String uri = "feedback://" + f.getId();
    String body = body(f);
    String title = f.getQuestion().strip().replaceAll("\\s+", " ");
    if (title.length() > 200) title = title.substring(0, 200);
    SourceItem item = new SourceItem(UUID.randomUUID(), run.getId(), uri, uri, 0);
    item.assignCrate(run.getCrateId()); item.status(FrontierStatus.FETCHED); items.save(item);
    String hash = Hashing.sha256(body);
    AcquisitionRecord acquisition = new AcquisitionRecord(UUID.randomUUID(), run.getId(), item.getId(), uri);
    acquisition.assignCrate(run.getCrateId());
    acquisition.success(uri, 200, "text/markdown", "UTF-8", null, hash, body.getBytes(StandardCharsets.UTF_8).length, 0);
    acquisitions.save(acquisition);
    String metadata = "{\"kind\":\"feedback-qa\",\"feedbackId\":\"" + f.getId() + "\",\"quality\":" + f.getQuality() + ",\"relevance\":" + f.getRelevance() + "}";
    UUID documentId = UUID.nameUUIDFromBytes(("feedback:" + f.getId()).getBytes(StandardCharsets.UTF_8));
    var document = new NormalizedDocument(documentId, run.getId(), acquisition.getId(), uri, title, null, null, "User feedback", body, hash, metadata);
    document.assignCrate(run.getCrateId());
    document.version(run.getSourceId(), uri, 1);
    documents.save(document);
    var chunk = new DocumentChunk(UUID.nameUUIDFromBytes(("chunk:" + documentId + ":0").getBytes(StandardCharsets.UTF_8)), documentId, 0, title, body, hash);
    chunk.assignCrate(run.getCrateId());
    chunks.save(chunk);
    f.indexedAs(documentId);
    feedback.save(f);
    UUID crateId = run.getCrateId(), runId = run.getId();
    afterCommit(() -> queue.publish(PipelineMessage.create(crateId, WorkStage.INDEX, IngestionService.payload(crateId, runId, documentId), runId, crateId + ":index:" + documentId, 20)));
  }

  /** The synthetic document: the verified question and answer, plus the titles of the sources the answer was based on. */
  String body(AnswerFeedback f) {
    String answer = CITATION.matcher(f.getAnswer()).replaceAll("").strip();
    if (answer.length() > MAX_ANSWER_CHARACTERS) answer = answer.substring(0, MAX_ANSWER_CHARACTERS);
    var text = new StringBuilder("# Question\n\n").append(f.getQuestion().strip()).append("\n\n# Verified answer\n\n").append(answer).append('\n');
    var used = feedbackSources.findByFeedbackId(f.getId());
    if (!used.isEmpty()) {
      text.append("\n# Based on\n\n");
      for (var s : used) text.append("- ").append(s.getTitle() == null || s.getTitle().isBlank() ? s.getSourceUri() : s.getTitle()).append('\n');
    }
    return text.toString();
  }

  /** Removes the synthetic document of a feedback entry from the database and the index; a no-op when there is none. */
  @Transactional
  public void removeDocument(AnswerFeedback f) {
    UUID id = f.getEnrichedDocumentId();
    if (id == null) return;
    try { index.delete(f.getCrateId(), id); } catch (Exception e) { log.warn("Could not remove feedback document {} from the index: {}", id, e.toString()); }
    chunks.deleteByDocumentId(id);
    documents.findByIdAndCrateId(id, f.getCrateId()).ifPresent(documents::delete);
  }

  private Source feedbackSource(UUID crateId) {
    return sources.findByCrateId(crateId).stream().filter(s -> s.getConnectorType() == ConnectorType.FEEDBACK).findFirst()
        .orElseGet(() -> sources.save(new Source(UUID.randomUUID(), crateId, "User feedback", "Questions and answers rated by users", ConnectorType.FEEDBACK, "{}")));
  }

  private static void afterCommit(Runnable action) {
    if (TransactionSynchronizationManager.isSynchronizationActive())
      TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() { @Override public void afterCommit() { action.run(); } });
    else action.run();
  }
}
