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
  public static final int MAX_REPORT_CASES = 5_000, MAX_REPORT_ROWS = 200, TOP_N = 15, TOP_TOPICS = 25, TOP_GAP_TOPICS = 10;
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
      String judgeModel, String error, String topic, List<String> tags, boolean ignored, TextView text) {}

  /** One topic or tag in the report with how often it was asked and how well it was answered. */
  public record Ranked(String label, long count, Double faithfulness, Double answerRelevance, Double completeness, List<String> variants) {}

  /** Judge-written reading of the report's numbers. */
  public record ReportSummary(String overview, List<String> well, List<String> poorly, List<String> retrieval, List<String> gaps,
      List<String> suggestions, String model, Instant generatedAt) {}

  /** The reporting page: everything for one crate and one time frame. {@code truncated} when more than MAX_REPORT_CASES matched. */
  public record Report(Instant from, Instant to, long questions, long failed, boolean truncated, Averages live, Averages golden,
      List<Day> trend, List<Ranked> topTopics, List<Ranked> topTags, List<CaseView> latest, long withTopic,
      String groupingMethod, ReportSummary summary, GapReport gaps, EvaluationIgnoreService.Summary ignored) {
    /** The most asked topics that were answered well: grounded, relevant and complete (scores that were not recorded do not count against a topic). */
    public List<Ranked> wellAnswered() {
      return topTopics.stream().filter(t -> t.faithfulness() != null && t.faithfulness() >= 0.8
          && (t.answerRelevance() == null || t.answerRelevance() >= 0.7) && (t.completeness() == null || t.completeness() >= 0.7))
          .limit(TOP_GAP_TOPICS).toList();
    }
    public Report withSummary(ReportSummary value) {
      return new Report(from, to, questions, failed, truncated, live, golden, trend, topTopics, topTags, latest, withTopic, groupingMethod, value, gaps, ignored);
    }
    /** The per-rule totals of ignored messages; shown only when asked for. */
    public Report withIgnored(EvaluationIgnoreService.Summary value) {
      return new Report(from, to, questions, failed, truncated, live, golden, trend, topTopics, topTags, latest, withTopic, groupingMethod, summary, gaps, value);
    }
    public Report withGapSummary(GapSummary value) {
      return new Report(from, to, questions, failed, truncated, live, golden, trend, topTopics, topTags, latest, withTopic, groupingMethod, summary, gaps.withSummary(value), ignored);
    }
  }

  /** A topic (grouped by meaning) that users asked about and the knowledge base could not answer, or only in part. */
  public record GapTopic(String label, long count, long noSources, long notInSources, long partial, List<String> variants, List<String> tags, Instant lastAsked) {}

  /** One question that was not answered; {@code question} only while its text is still stored and the viewer may see it. */
  public record GapQuestion(Instant createdAt, String status, String reason, String topic, List<String> tags, String question) {}

  /**
   * Questions the knowledge base could not answer. {@code tracked} is the number of live answers that were assessed
   * (zero when "track unanswered questions" is off or nothing was asked yet).
   */
  public record GapReport(long tracked, long unanswered, long partial, long noSources, long notInSources,
      List<GapTopic> topics, List<GapQuestion> latest, GapSummary summary) {
    GapReport withSummary(GapSummary value) { return new GapReport(tracked, unanswered, partial, noSources, notInSources, topics, latest, value); }
    public long gaps() { return unanswered + partial; }
    /** Share of assessed questions that were unanswered or partly answered, 0..1. */
    public double share() { return tracked == 0 ? 0 : (double) (unanswered + partial) / tracked; }
  }

  /** Judge-written reading of the unanswered questions. */
  public record GapSummary(String overview, List<String> themes, List<String> causes, List<String> actions, String model, Instant generatedAt) {}

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
  private final EvaluationIgnoreService ignore;

  public EvaluationAdminService(EvaluationCaseRepository cases, EvaluationGoldenItemRepository golden,
      EvaluationSettingsService settings, EvaluationCollector collector, AuditLogRepository audits, TopicGrouper grouper, JudgeClient judge,
      EvaluationIgnoreService ignore) {
    this.cases = cases; this.golden = golden; this.settings = settings; this.collector = collector; this.audits = audits;
    this.grouper = grouper; this.judge = judge; this.ignore = ignore;
  }

  // ------------------------------------------------------------------ summary and cases

  @Transactional(readOnly = true)
  public Summary summary(UUID crateId, int days) {
    int window = Math.max(1, Math.min(days, 365));
    Instant since = Instant.now().minus(window, ChronoUnit.DAYS);
    List<EvaluationCase> done = cases.findByCrateIdAndStatusAndIgnoredRuleIdIsNullAndCreatedAtAfter(crateId, EvaluationCase.Status.DONE, since);
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
    List<EvaluationCase> found = new ArrayList<>(cases.findByCrateIdAndStatusAndIgnoredRuleIdIsNullAndCreatedAtBetweenOrderByCreatedAtDesc(
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
    GapReport gaps = gaps(live, grouping.clusters(), byWording, includeText);
    return new Report(from, to, live.size(), cases.countByCrateIdAndStatus(crateId, EvaluationCase.Status.FAILED), truncated,
        averages(live), averages(goldenCases), trend, topics, ranked(tags), latest, withTopic, grouping.method(), null, gaps, null);
  }

  /** Counts and clusters the live cases that were not (fully) answered, reusing the meaning clusters of the topics. */
  private static GapReport gaps(List<EvaluationCase> live, List<List<String>> clusters, Map<String, List<EvaluationCase>> byWording, boolean includeText) {
    long tracked = live.stream().filter(c -> c.getAnswerStatus() != null).count();
    List<EvaluationCase> gapCases = live.stream().filter(EvaluationCase::isGap).toList();
    long unanswered = gapCases.stream().filter(c -> "UNANSWERED".equals(c.getAnswerStatus())).count();
    long noSources = gapCases.stream().filter(c -> "no-sources".equals(c.getGapReason())).count();
    long notInSources = gapCases.stream().filter(c -> "not-in-sources".equals(c.getGapReason())).count();
    List<GapTopic> topics = new ArrayList<>();
    for (List<String> cluster : clusters) {
      List<EvaluationCase> members = cluster.stream().flatMap(w -> byWording.get(w).stream()).filter(EvaluationCase::isGap).toList();
      if (members.isEmpty()) continue;
      Map<String, Long> tagCounts = new HashMap<>();
      members.forEach(c -> readTags(c.getTags()).forEach(t -> tagCounts.merge(t, 1L, Long::sum)));
      List<String> tags = tagCounts.entrySet().stream().sorted(Map.Entry.<String, Long>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
          .limit(5).map(Map.Entry::getKey).toList();
      topics.add(new GapTopic(cluster.get(0), members.size(), members.stream().filter(c -> "no-sources".equals(c.getGapReason())).count(),
          members.stream().filter(c -> "not-in-sources".equals(c.getGapReason())).count(), members.stream().filter(c -> "partial".equals(c.getGapReason())).count(),
          cluster.stream().skip(1).limit(5).toList(), tags, members.stream().map(EvaluationCase::getCreatedAt).max(Comparator.naturalOrder()).orElse(null)));
    }
    topics.sort(Comparator.comparingLong(GapTopic::count).reversed().thenComparing(GapTopic::label));
    List<GapQuestion> latest = gapCases.stream().limit(MAX_REPORT_ROWS)
        .map(c -> new GapQuestion(c.getCreatedAt(), c.getAnswerStatus(), c.getGapReason(), c.getTopic(), readTags(c.getTags()), includeText ? c.getQuestion() : null)).toList();
    return new GapReport(tracked, unanswered, gapCases.size() - unanswered, noSources, notInSources,
        topics.stream().limit(TOP_GAP_TOPICS).toList(), latest, null);
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
  private static final int SUMMARY_CACHE_SIZE = 100, MAX_ITEMS = 10, MAX_TEXT = 900;
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
        items(node.path("retrieval")), items(node.path("gaps")), items(node.path("suggestions")), judge.model(crateId), now);
    if (summary.overview().isEmpty() && summary.well().isEmpty() && summary.poorly().isEmpty() && summary.retrieval().isEmpty()
        && summary.gaps().isEmpty() && summary.suggestions().isEmpty())
      throw new IllegalStateException("The judge returned an empty summary");
    if (summaries.size() >= SUMMARY_CACHE_SIZE) summaries.clear();
    summaries.put(key, new Cached(summary, now));
    return summary;
  }

  private record CachedGap(GapSummary summary, Instant at) {}
  private final Map<String, CachedGap> gapSummaries = new java.util.concurrent.ConcurrentHashMap<>();

  /**
   * Asks the judge model what the unanswered questions say about missing content. It receives only the clustered topics
   * with counts and reasons, never questions or answers. Cached for an hour like {@link #summarize}.
   */
  public GapSummary summarizeGaps(UUID crateId, Report report) throws Exception {
    if (!judge.available(crateId)) throw new IllegalStateException("Configure a judge model to analyze the unanswered questions");
    GapReport gaps = report.gaps();
    if (gaps == null || gaps.gaps() == 0) throw new IllegalStateException("There are no unanswered questions in this timeframe to analyze");
    String input = gapInput(report);
    String key = crateId + "|gaps|" + report.from() + "|" + report.to() + "|" + input.hashCode();
    Instant now = Instant.now();
    CachedGap cached = gapSummaries.get(key);
    if (cached != null && cached.at().plusSeconds(SUMMARY_TTL_SECONDS).isAfter(now)) return cached.summary();
    JsonNode node = JudgeProtocol.askJson(judge, crateId, JudgeProtocol.GAP_SUMMARY, JudgeProtocol.textInput("DATA", input));
    var summary = new GapSummary(text(node.path("overview").asText("")), items(node.path("themes")), items(node.path("causes")),
        items(node.path("actions")), judge.model(crateId), now);
    if (summary.overview().isEmpty() && summary.themes().isEmpty() && summary.causes().isEmpty() && summary.actions().isEmpty())
      throw new IllegalStateException("The judge returned an empty analysis");
    if (gapSummaries.size() >= SUMMARY_CACHE_SIZE) gapSummaries.clear();
    gapSummaries.put(key, new CachedGap(summary, now));
    return summary;
  }

  static String gapInput(Report r) {
    GapReport g = r.gaps();
    StringBuilder b = new StringBuilder();
    b.append("Period: ").append(r.from()).append(" to ").append(r.to()).append(" (UTC)\n");
    b.append("Questions assessed: ").append(g.tracked()).append(", not answered: ").append(g.unanswered()).append(", answered in part: ").append(g.partial()).append('\n');
    b.append("Reasons: no-sources ").append(g.noSources()).append(", not-in-sources ").append(g.notInSources()).append(", partial ").append(g.partial()).append('\n');
    b.append("Topics that were not (fully) answered, most asked first:\n");
    for (GapTopic t : g.topics()) {
      b.append("- \"").append(t.label()).append("\" asked ").append(t.count()).append("x (no-sources ").append(t.noSources()).append(", not-in-sources ")
          .append(t.notInSources()).append(", partial ").append(t.partial()).append(')');
      if (!t.tags().isEmpty()) b.append(", tags: ").append(String.join(", ", t.tags()));
      b.append('\n');
    }
    return b.toString();
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
    Averages l = r.live();
    b.append("Retrieval quality (live answers): precision@K ").append(num(l.precisionAtK())).append(", recall@K ").append(num(l.recallAtK()))
        .append(", MRR ").append(num(l.mrr())).append(", MAP ").append(num(l.map())).append(", NDCG ").append(num(l.ndcg())).append('\n');
    if (r.golden() != null && r.golden().cases() > 0)
      b.append("Golden set (").append(r.golden().cases()).append(" questions): ").append(scores(r.golden().faithfulness(), r.golden().answerRelevance(), r.golden().completeness()))
          .append(", reference recall ").append(num(r.golden().referenceRecall())).append('\n');
    b.append("Evaluations that failed (all time): ").append(r.failed()).append('\n');
    if (!r.trend().isEmpty()) {
      Day first = r.trend().get(0), last = r.trend().get(r.trend().size() - 1);
      b.append("Trend: ").append(r.trend().size()).append(" days with data; faithfulness on ").append(first.day()).append(" ").append(num(first.averages().faithfulness()))
          .append(" (").append(first.averages().cases()).append(" answers), on ").append(last.day()).append(" ").append(num(last.averages().faithfulness()))
          .append(" (").append(last.averages().cases()).append(" answers)\n");
    }
    GapReport g = r.gaps();
    if (g != null && g.tracked() > 0) {
      b.append("Unanswered questions: ").append(g.unanswered()).append(" not answered and ").append(g.partial()).append(" partly answered of ").append(g.tracked())
          .append(" assessed; nothing found ").append(g.noSources()).append(", not in documents ").append(g.notInSources()).append('\n');
      for (GapTopic t : g.topics()) b.append("- gap: \"").append(t.label()).append("\" ").append(t.count()).append("x (nothing found ").append(t.noSources())
          .append(", not in documents ").append(t.notInSources()).append(", partial ").append(t.partial()).append(")\n");
    }
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
  public List<CaseView> cases(UUID crateId, int limit, boolean includeText) { return cases(crateId, limit, includeText, false); }

  /** The newest cases; cases caught by an ignore rule are left out unless {@code showIgnored}. */
  @Transactional(readOnly = true)
  public List<CaseView> cases(UUID crateId, int limit, boolean includeText, boolean showIgnored) {
    int size = Math.max(1, Math.min(limit, MAX_CASES));
    var page = PageRequest.of(0, size);
    var found = showIgnored ? cases.findByCrateIdOrderByCreatedAtDesc(crateId, page) : cases.findByCrateIdAndIgnoredRuleIdIsNullOrderByCreatedAtDesc(crateId, page);
    return found.stream().map(c -> view(c, includeText)).toList();
  }

  // ------------------------------------------------------------------ ignore rules

  public List<EvaluationIgnoreService.RuleView> ignoreRules(UUID crateId) { return ignore.views(crateId); }

  public void addIgnoreRules(UUID crateId, String actor, String kind, List<String> patterns, boolean containsMatch) {
    ignore.addAll(crateId, actor, kind, patterns, containsMatch);
  }

  public void deleteIgnoreRule(UUID crateId, String actor, UUID id) { ignore.delete(crateId, actor, id); }

  /** Messages caught per ignore rule within {@code [from, to)}: the rule and a number, nothing else. */
  public EvaluationIgnoreService.Summary ignoredSummary(UUID crateId, Instant from, Instant to) { return ignore.summary(crateId, from, to); }

  static CaseView view(EvaluationCase c, boolean includeText) {
    TextView text = null;
    if (includeText && c.hasText()) text = new TextView(c.getQuestion(), c.getAnswer(), map(c.getSnapshotJson()), map(c.getDetailsJson()), pretty(c.getDetailsJson()));
    return new CaseView(c.getId(), c.getCreatedAt(), c.getSource().name(), c.getStatus().name(), c.getK(), c.getPrecisionAtK(),
        c.getRecallAtK(), c.getMrr(), c.getAveragePrecision(), c.getNdcg(), c.getFaithfulness(), c.getStatementsTotal(),
        c.getStatementsSupported(), c.getAnswerRelevance(), c.getCompleteness(), c.getReferenceRecall(), c.getJudgeModel(), c.getError(), c.getTopic(), readTags(c.getTags()), c.isIgnored(), text);
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
