package tech.wenisch.contextcrate.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.Function;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tech.wenisch.contextcrate.domain.AuditLog;
import tech.wenisch.contextcrate.domain.EvaluationCase;
import tech.wenisch.contextcrate.domain.EvaluationGoldenItem;
import tech.wenisch.contextcrate.repository.AuditLogRepository;
import tech.wenisch.contextcrate.repository.EvaluationCaseRepository;
import tech.wenisch.contextcrate.repository.EvaluationGoldenItemRepository;

/**
 * What the evaluation dashboard shows and does: averages and a daily trend over stored cases, the
 * per-question list, and the golden set. Callers must already have checked for an administrator.
 * Text (question, answer, chunks, judge reasoning) is only returned when {@code includeText} is
 * set, which the callers do only while the administrator has an active elevation for the crate.
 */
@Service
public class EvaluationAdminService {
  public static final int MAX_GOLDEN_ITEMS = 200, MAX_CASES = 200, MAX_QUESTION = 2_000, MAX_REFERENCE = 8_000;
  private static final ObjectMapper MAPPER = new ObjectMapper();

  /** Averages over finished cases; a value is {@code null} when no case recorded it. */
  public record Averages(long cases, Double precisionAtK, Double recallAtK, Double mrr, Double map, Double ndcg,
      Double faithfulness, Double answerRelevance, Double completeness, Double referenceRecall) {}

  public record Day(LocalDate day, Averages averages) {}

  public record Summary(int days, long pending, long failed, Averages live, Averages golden, List<Day> trend) {}

  public record CaseView(UUID id, Instant createdAt, String source, String status, Integer k, Double precisionAtK,
      Double recallAtK, Double mrr, Double map, Double ndcg, Double faithfulness, Integer statementsTotal,
      Integer statementsSupported, Double answerRelevance, Double completeness, Double referenceRecall,
      String judgeModel, String error, TextView text) {}

  /** Only present while the case still carries its text (per-question logging) and the viewer may see it. */
  public record TextView(String question, String answer, JsonNode snapshot, JsonNode details) {}

  public record GoldenView(UUID id, String question, String referenceAnswer, List<String> relevantUris, Instant createdAt) {}

  private final EvaluationCaseRepository cases;
  private final EvaluationGoldenItemRepository golden;
  private final EvaluationSettingsService settings;
  private final EvaluationCollector collector;
  private final AuditLogRepository audits;

  public EvaluationAdminService(EvaluationCaseRepository cases, EvaluationGoldenItemRepository golden,
      EvaluationSettingsService settings, EvaluationCollector collector, AuditLogRepository audits) {
    this.cases = cases; this.golden = golden; this.settings = settings; this.collector = collector; this.audits = audits;
  }

  // ------------------------------------------------------------------ summary and cases

  @Transactional(readOnly = true)
  public Summary summary(UUID crateId, int days) {
    int window = Math.max(1, Math.min(days, 365));
    Instant since = Instant.now().minus(window, ChronoUnit.DAYS);
    List<EvaluationCase> done = cases.findByCrateIdAndStatusAndCreatedAtAfter(crateId, EvaluationCase.Status.DONE, since);
    List<EvaluationCase> live = done.stream().filter(c -> c.getSource() == EvaluationCase.Source.LIVE).toList();
    List<EvaluationCase> goldenCases = done.stream().filter(c -> c.getSource() == EvaluationCase.Source.GOLDEN).toList();
    Map<LocalDate, List<EvaluationCase>> byDay = new TreeMap<>();
    for (EvaluationCase c : live) byDay.computeIfAbsent(c.getCreatedAt().atZone(ZoneOffset.UTC).toLocalDate(), d -> new ArrayList<>()).add(c);
    List<Day> trend = byDay.entrySet().stream().map(e -> new Day(e.getKey(), averages(e.getValue()))).toList();
    return new Summary(window, cases.countByCrateIdAndStatus(crateId, EvaluationCase.Status.PENDING),
        cases.countByCrateIdAndStatus(crateId, EvaluationCase.Status.FAILED), averages(live), averages(goldenCases), trend);
  }

  static Averages averages(List<EvaluationCase> list) {
    return new Averages(list.size(), avg(list, EvaluationCase::getPrecisionAtK), avg(list, EvaluationCase::getRecallAtK),
        avg(list, EvaluationCase::getMrr), avg(list, EvaluationCase::getAveragePrecision), avg(list, EvaluationCase::getNdcg),
        avg(list, EvaluationCase::getFaithfulness), avg(list, EvaluationCase::getAnswerRelevance),
        avg(list, EvaluationCase::getCompleteness), avg(list, EvaluationCase::getReferenceRecall));
  }

  private static Double avg(List<EvaluationCase> list, Function<EvaluationCase, Double> value) {
    return RankingMetrics.mean(list.stream().map(value).toList());
  }

  @Transactional(readOnly = true)
  public List<CaseView> cases(UUID crateId, int limit, boolean includeText) {
    int size = Math.max(1, Math.min(limit, MAX_CASES));
    return cases.findByCrateIdOrderByCreatedAtDesc(crateId, PageRequest.of(0, size)).stream().map(c -> view(c, includeText)).toList();
  }

  static CaseView view(EvaluationCase c, boolean includeText) {
    TextView text = null;
    if (includeText && c.hasText()) text = new TextView(c.getQuestion(), c.getAnswer(), json(c.getSnapshotJson()), json(c.getDetailsJson()));
    return new CaseView(c.getId(), c.getCreatedAt(), c.getSource().name(), c.getStatus().name(), c.getK(), c.getPrecisionAtK(),
        c.getRecallAtK(), c.getMrr(), c.getAveragePrecision(), c.getNdcg(), c.getFaithfulness(), c.getStatementsTotal(),
        c.getStatementsSupported(), c.getAnswerRelevance(), c.getCompleteness(), c.getReferenceRecall(), c.getJudgeModel(), c.getError(), text);
  }

  private static JsonNode json(String value) {
    if (value == null || value.isBlank()) return null;
    try { return MAPPER.readTree(value); } catch (Exception e) { return null; }
  }

  // ------------------------------------------------------------------ golden set

  @Transactional(readOnly = true)
  public List<GoldenView> golden(UUID crateId) {
    return golden.findByCrateIdOrderByCreatedAtAsc(crateId).stream().map(EvaluationAdminService::view).toList();
  }

  static GoldenView view(EvaluationGoldenItem item) {
    return new GoldenView(item.getId(), item.getQuestion(), item.getReferenceAnswer(), EvaluationService.readUris(item.getRelevantUris()), item.getCreatedAt());
  }

  @Transactional
  public GoldenView addGolden(UUID crateId, String actor, String question, String referenceAnswer, List<String> relevantUris) {
    GoldenView added = add(crateId, question, referenceAnswer, relevantUris);
    audits.save(new AuditLog(crateId, actor, "EVALUATION_GOLDEN_CHANGED", added.id().toString(), "added"));
    return added;
  }

  /** Accepts a JSON array of {question, referenceAnswer, relevantUris}; the whole import is rejected if one entry is invalid. */
  @Transactional
  public int importGolden(UUID crateId, String actor, String json) {
    JsonNode root;
    try { root = MAPPER.readTree(json); } catch (Exception e) { throw new IllegalArgumentException("The golden set must be valid JSON"); }
    if (root == null || !root.isArray()) throw new IllegalArgumentException("The golden set must be a JSON array");
    int count = 0;
    for (JsonNode node : root) {
      List<String> uris = new ArrayList<>();
      for (JsonNode uri : node.path("relevantUris")) uris.add(uri.asText(""));
      add(crateId, node.path("question").asText(""), node.path("referenceAnswer").asText(null), uris);
      count++;
    }
    audits.save(new AuditLog(crateId, actor, "EVALUATION_GOLDEN_CHANGED", "import", count + " items"));
    return count;
  }

  private GoldenView add(UUID crateId, String question, String referenceAnswer, List<String> relevantUris) {
    String q = question == null ? "" : question.strip();
    if (q.isEmpty() || q.length() > MAX_QUESTION) throw new IllegalArgumentException("A golden question is required (at most 2000 characters)");
    String reference = referenceAnswer == null || referenceAnswer.isBlank() ? null : referenceAnswer.strip();
    if (reference != null && reference.length() > MAX_REFERENCE) throw new IllegalArgumentException("The reference answer is too long");
    List<String> uris = relevantUris == null ? List.of() : relevantUris.stream().filter(u -> u != null && !u.isBlank()).map(String::strip).distinct().toList();
    if (reference == null && uris.isEmpty()) throw new IllegalArgumentException("Give expected documents, a reference answer, or both");
    if (golden.countByCrateId(crateId) >= MAX_GOLDEN_ITEMS) throw new IllegalArgumentException("A crate can have at most " + MAX_GOLDEN_ITEMS + " golden questions");
    try {
      var item = golden.save(new EvaluationGoldenItem(UUID.randomUUID(), crateId, q, reference, uris.isEmpty() ? null : MAPPER.writeValueAsString(uris)));
      return view(item);
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalArgumentException("Invalid expected documents");
    }
  }

  @Transactional
  public void deleteGolden(UUID crateId, String actor, UUID id) {
    golden.findById(id).filter(i -> i.getCrateId().equals(crateId)).ifPresent(i -> {
      golden.delete(i);
      audits.save(new AuditLog(crateId, actor, "EVALUATION_GOLDEN_CHANGED", id.toString(), "deleted"));
    });
  }

  /** Queues one evaluation per golden question; the worker answers each through the normal answer path. */
  @Transactional
  public int runGolden(UUID crateId, String actor) throws Exception {
    if (!settings.judgeAvailable(crateId)) throw new IllegalStateException("Configure a judge model before running the golden set");
    List<EvaluationGoldenItem> items = golden.findByCrateIdOrderByCreatedAtAsc(crateId);
    if (items.isEmpty()) throw new IllegalStateException("The golden set is empty");
    for (EvaluationGoldenItem item : items) {
      UUID caseId = UUID.randomUUID();
      cases.save(new EvaluationCase(caseId, crateId, EvaluationCase.Source.GOLDEN, item.getId(), item.getQuestion(), null, null));
      collector.publish(crateId, caseId);
    }
    audits.save(new AuditLog(crateId, actor, "EVALUATION_GOLDEN_RUN", "golden", items.size() + " questions"));
    return items.size();
  }
}
