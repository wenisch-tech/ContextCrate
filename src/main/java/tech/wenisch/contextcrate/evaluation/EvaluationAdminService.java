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
  public static final int MAX_REPORT_CASES = 5_000, MAX_REPORT_ROWS = 200, TOP_N = 15, TOP_TOPICS = 25;
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
      String judgeModel, String error, String topic, List<String> tags, TextView text) {}

  /** One topic or tag in the report with how often it was asked and how well it was answered. */
  public record Ranked(String label, long count, Double faithfulness, Double answerRelevance, Double completeness, List<String> variants) {}

  /** Judge-written reading of the report's numbers. */
  public record ReportSummary(String overview, List<String> well, List<String> poorly, List<String> suggestions, String model, Instant generatedAt) {}

  /** The reporting page: everything for one crate and one time frame. {@code truncated} when more than MAX_REPORT_CASES matched. */
  public record Report(Instant from, Instant to, long questions, long failed, boolean truncated, Averages live, Averages golden,
      List<Day> trend, List<Ranked> topTopics, List<Ranked> topTags, List<CaseView> latest, long withTopic,
      String groupingMethod, ReportSummary summary) {
    public Report withSummary(ReportSummary value) {
      return new Report(from, to, questions, failed, truncated, live, golden, trend, topTopics, topTags, latest, withTopic, groupingMethod, value);
    }
  }

  /** Only present while the case still carries its text (per-question logging) and the viewer may see it. */
  public record TextView(String question, String answer, Map<String, Object> snapshot, Map<String, Object> details, String detailsText) {}

  public record GoldenView(UUID id, String question, String referenceAnswer, List<String> relevantUris, Instant createdAt) {}

  private final EvaluationCaseRepository cases;
  private final EvaluationGoldenItemRepository golden;
  private final EvaluationSettingsService settings;
  private final EvaluationCollector collector;
  private final AuditLogRepository audits;
  private final TopicGrouper grouper;
  private final JudgeClient judge;

  public EvaluationAdminService(EvaluationCaseRepository cases, EvaluationGoldenItemRepository golden,
      EvaluationSettingsService settings, EvaluationCollector collector, AuditLogRepository audits, TopicGrouper grouper, JudgeClient judge) {
    this.cases = cases; this.golden = golden; this.settings = settings; this.collector = collector; this.audits = audits;
    this.grouper = grouper; this.judge = judge;
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

  /**
   * Report over {@code [from, to)}. Topics and tags are grouped over all matching live cases (at most
   * {@link #MAX_REPORT_CASES}); the question list shows the newest {@link #MAX_REPORT_ROWS}.
   */
  // Not transactional on purpose: grouping may call an embedding or judge endpoint, which must not hold a database connection.
  public Report report(UUID crateId, Instant from, Instant to, boolean includeText) {
    List<EvaluationCase> found = new ArrayList<>(cases.findByCrateIdAndStatusAndCreatedAtBetweenOrderByCreatedAtDesc(
        crateId, EvaluationCase.Status.DONE, from, to, PageRequest.of(0, MAX_REPORT_CASES + 1)));
    boolean truncated = found.size() > MAX_REPORT_CASES;
    if (truncated) found = found.subList(0, MAX_REPORT_CASES);
    List<EvaluationCase> live = found.stream().filter(c -> c.getSource() == EvaluationCase.Source.LIVE).toList();
    List<EvaluationCase> goldenCases = found.stream().filter(c -> c.getSource() == EvaluationCase.Source.GOLDEN).toList();
    Map<LocalDate, List<EvaluationCase>> byDay = new TreeMap<>();
    for (EvaluationCase c : live) byDay.computeIfAbsent(c.getCreatedAt().atZone(ZoneOffset.UTC).toLocalDate(), d -> new ArrayList<>()).add(c);
    List<Day> trend = byDay.entrySet().stream().map(e -> new Day(e.getKey(), averages(e.getValue()))).toList();

    Map<String, List<EvaluationCase>> byWording = new LinkedHashMap<>(), tags = new LinkedHashMap<>();
    long withTopic = 0;
    for (EvaluationCase c : live) {
      if (c.getTopic() != null) {
        withTopic++;
        byWording.computeIfAbsent(c.getTopic(), k -> new ArrayList<>()).add(c);
      }
      for (String tag : readTags(c.getTags())) tags.computeIfAbsent(tag, k -> new ArrayList<>()).add(c);
    }
    // Wordings that mean the same are reported as one topic; the most frequent wording is its label.
    List<String> wordings = byWording.entrySet().stream()
        .sorted(Comparator.comparingInt((Map.Entry<String, List<EvaluationCase>> e) -> e.getValue().size()).reversed().thenComparing(Map.Entry::getKey))
        .map(Map.Entry::getKey).toList();
    var grouping = grouper.group(crateId, wordings);
    List<Ranked> topics = grouping.clusters().stream().map(cluster -> {
      List<EvaluationCase> members = cluster.stream().flatMap(w -> byWording.get(w).stream()).toList();
      return new Ranked(cluster.get(0), members.size(), avg(members, EvaluationCase::getFaithfulness),
          avg(members, EvaluationCase::getAnswerRelevance), avg(members, EvaluationCase::getCompleteness),
          cluster.stream().skip(1).limit(5).toList());
    }).sorted(Comparator.comparingLong(Ranked::count).reversed().thenComparing(Ranked::label)).limit(TOP_TOPICS).toList();
    List<CaseView> latest = live.stream().limit(MAX_REPORT_ROWS).map(c -> view(c, includeText)).toList();
    return new Report(from, to, live.size(), cases.countByCrateIdAndStatus(crateId, EvaluationCase.Status.FAILED), truncated,
        averages(live), averages(goldenCases), trend, topics, ranked(tags), latest, withTopic, grouping.method(), null);
  }

  private static List<Ranked> ranked(Map<String, List<EvaluationCase>> groups) {
    return groups.entrySet().stream()
        .map(e -> new Ranked(e.getKey(), e.getValue().size(), avg(e.getValue(), EvaluationCase::getFaithfulness),
            avg(e.getValue(), EvaluationCase::getAnswerRelevance), avg(e.getValue(), EvaluationCase::getCompleteness), List.of()))
        .sorted(Comparator.comparingLong(Ranked::count).reversed().thenComparing(Ranked::label))
        .limit(TOP_N).toList();
  }

  // ------------------------------------------------------------------ summary

  private static final long SUMMARY_TTL_SECONDS = 3600;
  private static final int SUMMARY_CACHE_SIZE = 100, MAX_ITEMS = 6, MAX_TEXT = 500;
  private record Cached(ReportSummary summary, Instant at) {}
  private final Map<String, Cached> summaries = new java.util.concurrent.ConcurrentHashMap<>();

  /**
   * Asks the judge model to read the report. It receives only aggregated numbers and topic wordings,
   * never questions or answers. The result is kept for an hour per crate, timeframe and data, so a
   * reload or the printable view does not ask again.
   */
  public ReportSummary summarize(UUID crateId, Report report) throws Exception {
    if (!judge.available(crateId)) throw new IllegalStateException("Configure a judge model to generate a summary");
    if (report.questions() == 0) throw new IllegalStateException("There are no evaluated answers in this timeframe to summarize");
    String input = summaryInput(report);
    String key = crateId + "|" + report.from() + "|" + report.to() + "|" + input.hashCode();
    Instant now = Instant.now();
    Cached cached = summaries.get(key);
    if (cached != null && cached.at().plusSeconds(SUMMARY_TTL_SECONDS).isAfter(now)) return cached.summary();
    JsonNode node = JudgeProtocol.askJson(judge, crateId, JudgeProtocol.REPORT_SUMMARY, JudgeProtocol.textInput("DATA", input));
    var summary = new ReportSummary(text(node.path("overview").asText("")), items(node.path("well")), items(node.path("poorly")),
        items(node.path("suggestions")), judge.model(crateId), now);
    if (summary.overview().isEmpty() && summary.well().isEmpty() && summary.poorly().isEmpty() && summary.suggestions().isEmpty())
      throw new IllegalStateException("The judge returned an empty summary");
    if (summaries.size() >= SUMMARY_CACHE_SIZE) summaries.clear();
    summaries.put(key, new Cached(summary, now));
    return summary;
  }

  private static String text(String value) {
    String clean = value == null ? "" : value.replaceAll("\\s+", " ").strip();
    return clean.length() > MAX_TEXT ? clean.substring(0, MAX_TEXT).strip() : clean;
  }

  private static List<String> items(JsonNode node) {
    List<String> out = new ArrayList<>();
    if (node.isArray()) for (JsonNode n : node) { String t = text(n.asText("")); if (!t.isEmpty() && out.size() < MAX_ITEMS) out.add(t); }
    return out;
  }

  /** The compact facts the summary is based on; also what the owner can verify in the tables of the report. */
  static String summaryInput(Report r) {
    StringBuilder b = new StringBuilder();
    b.append("Period: ").append(r.from()).append(" to ").append(r.to()).append(" (UTC)\n");
    b.append("Questions answered and evaluated: ").append(r.questions()).append(", of which with a stored topic: ").append(r.withTopic()).append('\n');
    b.append("Overall: ").append(scores(r.live().faithfulness(), r.live().answerRelevance(), r.live().completeness())).append('\n');
    b.append("Topics (grouped by meaning, most asked first):\n");
    for (Ranked t : r.topTopics()) b.append("- \"").append(t.label()).append("\" asked ").append(t.count()).append("x, ").append(scores(t.faithfulness(), t.answerRelevance(), t.completeness())).append('\n');
    b.append("Tags:\n");
    for (Ranked t : r.topTags()) b.append("- ").append(t.label()).append(" ").append(t.count()).append("x, ").append(scores(t.faithfulness(), t.answerRelevance(), t.completeness())).append('\n');
    return b.toString();
  }

  private static String scores(Double faithfulness, Double relevance, Double completeness) {
    return "faithfulness " + num(faithfulness) + ", answer relevance " + num(relevance) + ", completeness " + num(completeness);
  }

  private static String num(Double value) { return value == null ? "n/a" : String.format(Locale.ROOT, "%.2f", value); }

  static List<String> readTags(String json) {
    if (json == null || json.isBlank()) return List.of();
    try {
      List<String> out = new ArrayList<>();
      for (JsonNode n : MAPPER.readTree(json)) out.add(n.asText());
      return out;
    } catch (Exception e) { return List.of(); }
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
    if (includeText && c.hasText()) text = new TextView(c.getQuestion(), c.getAnswer(), map(c.getSnapshotJson()), map(c.getDetailsJson()), pretty(c.getDetailsJson()));
    return new CaseView(c.getId(), c.getCreatedAt(), c.getSource().name(), c.getStatus().name(), c.getK(), c.getPrecisionAtK(),
        c.getRecallAtK(), c.getMrr(), c.getAveragePrecision(), c.getNdcg(), c.getFaithfulness(), c.getStatementsTotal(),
        c.getStatementsSupported(), c.getAnswerRelevance(), c.getCompleteness(), c.getReferenceRecall(), c.getJudgeModel(), c.getError(), c.getTopic(), readTags(c.getTags()), text);
  }

  /** Plain maps, not Jackson tree nodes: the web layer serialises with its own Jackson version. */
  @SuppressWarnings("unchecked")
  private static Map<String, Object> map(String value) {
    if (value == null || value.isBlank()) return null;
    try { return MAPPER.readValue(value, Map.class); } catch (Exception e) { return null; }
  }

  private static String pretty(String value) {
    if (value == null || value.isBlank()) return null;
    try { return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(MAPPER.readTree(value)); } catch (Exception e) { return null; }
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
