package tech.wenisch.contextcrate.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tech.wenisch.contextcrate.answer.AnswerService;
import tech.wenisch.contextcrate.domain.EvaluationCase;
import tech.wenisch.contextcrate.domain.PipelineTypes.WorkStage;
import tech.wenisch.contextcrate.metrics.CrateMetrics;
import tech.wenisch.contextcrate.queue.PipelineMessage;
import tech.wenisch.contextcrate.queue.PipelineQueue;
import tech.wenisch.contextcrate.repository.EvaluationCaseRepository;
import tech.wenisch.contextcrate.service.PipelinePayload;

/**
 * Hands finished answers over to the evaluation pipeline without ever delaying them.
 *
 * <p>{@link #offer} is called at the end of answer generation. It does nothing but put a small job
 * on a bounded queue of its own and return; a separate daemon thread decides (enabled? sampled?),
 * stores the snapshot and publishes a pipeline message that carries only the case id. When the
 * hand-over queue is full the answer is simply not evaluated and a counter is incremented.
 */
@Component
public class EvaluationCollector implements AutoCloseable {
  private static final Logger log = LoggerFactory.getLogger(EvaluationCollector.class);
  private static final ThreadLocal<Boolean> SUPPRESSED = new ThreadLocal<>();
  static final int HAND_OVER_CAPACITY = 200;

  private final EvaluationSettingsService settings;
  private final EvaluationCaseRepository cases;
  private final PipelineQueue queue;
  private final ObjectMapper mapper;
  private final ThreadPoolExecutor executor;
  private CrateMetrics metrics = CrateMetrics.noop();

  @Autowired
  public EvaluationCollector(EvaluationSettingsService settings, EvaluationCaseRepository cases, PipelineQueue queue, ObjectMapper mapper) {
    this(settings, cases, queue, mapper, new ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(HAND_OVER_CAPACITY), runnable -> {
      Thread thread = new Thread(runnable, "contextcrate-evaluation-collector");
      thread.setDaemon(true);
      return thread;
    }));
  }

  EvaluationCollector(EvaluationSettingsService settings, EvaluationCaseRepository cases, PipelineQueue queue, ObjectMapper mapper, ThreadPoolExecutor executor) {
    this.settings = settings; this.cases = cases; this.queue = queue; this.mapper = mapper; this.executor = executor;
  }

  @Autowired(required = false)
  public void setMetrics(CrateMetrics metrics) { this.metrics = metrics; }

  /** While open, answers generated on this thread are not offered for evaluation (golden questions asked by the worker). */
  public static AutoCloseable suppress() {
    Boolean previous = SUPPRESSED.get();
    SUPPRESSED.set(Boolean.TRUE);
    return () -> { if (previous == null) SUPPRESSED.remove(); else SUPPRESSED.set(previous); };
  }

  /** Never blocks, never throws. */
  public void offer(AnswerService.Prepared prepared, String answerText) {
    try {
      if (Boolean.TRUE.equals(SUPPRESSED.get()) || prepared == null || answerText == null || answerText.isBlank()) return;
      UUID crateId = prepared.crateId();
      executor.execute(() -> accept(crateId, prepared, answerText));
    } catch (RejectedExecutionException full) {
      metrics.count("evaluation.cases", prepared.crateId(), "outcome", "dropped", "source", "live");
    } catch (RuntimeException ignored) {
      // evaluation must never affect an answer
    }
  }

  private void accept(UUID crateId, AnswerService.Prepared prepared, String answerText) {
    try {
      var config = settings.current(crateId);
      if (!config.isEnabled() || !settings.judgeAvailable(crateId)) return;
      // Questions that found nothing are always kept when unanswered questions are tracked: they cost no judge scoring and are the gaps to close.
      boolean gap = config.isTrackUnanswered() && prepared.sources().isEmpty();
      if (!gap && config.getSamplingRate() < 1.0 && ThreadLocalRandom.current().nextDouble() >= config.getSamplingRate()) {
        metrics.count("evaluation.cases", crateId, "outcome", "skipped", "source", "live");
        return;
      }
      UUID caseId = UUID.randomUUID();
      cases.save(new EvaluationCase(caseId, crateId, EvaluationCase.Source.LIVE, null, prepared.question(), answerText,
          mapper.writeValueAsString(snapshot(prepared))));
      publish(crateId, caseId);
    } catch (Exception e) {
      metrics.count("evaluation.cases", crateId, "outcome", "dropped", "source", "live");
      log.warn("Could not queue an answer for evaluation. crateId={}: {}", crateId, e.getMessage());
    }
  }

  /** Publishes the work message for a stored case; the message carries only ids, never text. */
  public void publish(UUID crateId, UUID caseId) throws Exception {
    queue.publish(PipelineMessage.create(crateId, WorkStage.EVALUATE,
        mapper.writeValueAsString(new PipelinePayload(crateId, caseId, caseId)), caseId, "evaluate:" + caseId, 1));
  }

  static EvaluationSnapshot snapshot(AnswerService.Prepared prepared) {
    return new EvaluationSnapshot(prepared.mode(), prepared.searchQueries(), prepared.hydeQueries(),
        prepared.sources().stream().map(s -> new EvaluationSnapshot.Chunk(s.chunkId(), s.documentId(), s.title(), s.sourceUri(), s.content())).toList());
  }

  @Override
  public void close() { executor.shutdownNow(); }

  @jakarta.annotation.PreDestroy
  void shutdown() { close(); }
}
