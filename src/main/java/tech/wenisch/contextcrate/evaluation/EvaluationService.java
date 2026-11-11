package tech.wenisch.contextcrate.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import tech.wenisch.contextcrate.answer.AnswerService;
import tech.wenisch.contextcrate.config.CrateContext;
import tech.wenisch.contextcrate.domain.EvaluationCase;
import tech.wenisch.contextcrate.domain.EvaluationGoldenItem;
import tech.wenisch.contextcrate.domain.EvaluationSettings;
import tech.wenisch.contextcrate.embedding.EmbeddingProvider;
import tech.wenisch.contextcrate.index.SearchIndex;
import tech.wenisch.contextcrate.metrics.CrateMetrics;
import tech.wenisch.contextcrate.repository.EvaluationCaseRepository;
import tech.wenisch.contextcrate.repository.EvaluationGoldenItemRepository;

/**
 * Evaluates one answer along three axes, one dimension after another and one judge call per
 * question:
 * <ol>
 *   <li><b>Context relevance</b>: the judge grades every retrieved chunk 0-3; {@link RankingMetrics}
 *       turns the grades into Precision@K, Recall@K, MRR, MAP and NDCG.</li>
 *   <li><b>Faithfulness</b>: the answer is split into atomic statements and each is checked against
 *       the delivered chunks; score = supported / total.</li>
 *   <li><b>Answer relevance</b>: questions the answer would answer are generated and compared with
 *       the real question (embedding similarity, judge as fallback); completeness is the weighted
 *       share of the question's sub-questions that the answer addresses; with a golden item the
 *       reference answer is checked statement by statement.</li>
 * </ol>
 * A dimension that fails is recorded as failed without affecting the others. Runs on the pipeline
 * worker (stage {@code EVALUATE}), never on an answer thread.
 */
@Service
public class EvaluationService {
  private static final Logger log = LoggerFactory.getLogger(EvaluationService.class);
  static final int MAX_STATEMENTS = 20, MAX_SUB_QUESTIONS = 6, GENERATED_QUESTIONS = 3;
  static final String CORE = "core", BACKGROUND = "background", FOLLOW_UP = "follow_up";
  private static final ObjectMapper MAPPER = JudgeProtocol.MAPPER;

  private final EvaluationCaseRepository cases;
  private final EvaluationGoldenItemRepository golden;
  private final EvaluationSettingsService settings;
  private final JudgeClient judge;
  private final SearchIndex index;
  private final EmbeddingProvider embeddings;
  private AnswerService answers;
  private CrateMetrics metrics = CrateMetrics.noop();

  public EvaluationService(EvaluationCaseRepository cases, EvaluationGoldenItemRepository golden,
      EvaluationSettingsService settings, JudgeClient judge, SearchIndex index, EmbeddingProvider embeddings) {
    this.cases = cases; this.golden = golden; this.settings = settings; this.judge = judge; this.index = index; this.embeddings = embeddings;
  }

  @Autowired(required = false)
  public void setMetrics(CrateMetrics metrics) { this.metrics = metrics; }

  @Autowired(required = false)
  public void setAnswers(AnswerService answers) { this.answers = answers; }

  /** Evaluates the case; safe to call twice (a case that is no longer pending is left alone). */
  public void evaluate(UUID crateId, UUID caseId) throws Exception {
    var evaluation = cases.findById(caseId).orElse(null);
    if (evaluation == null || evaluation.getStatus() != EvaluationCase.Status.PENDING) return;
    if (!evaluation.getCrateId().equals(crateId)) throw new IllegalArgumentException("Pipeline message crosses crate boundary");
    long started = System.nanoTime();
    String source = evaluation.getSource().name().toLowerCase(Locale.ROOT);
    try (var ignored = CrateContext.use(crateId)) {
      EvaluationSettings config = settings.current(crateId);
      if (!judge.available(crateId)) {
        evaluation.failed("No judge model is configured");
        finishFailed(evaluation, config, source);
        return;
      }
      EvaluationGoldenItem item = evaluation.getGoldenItemId() == null ? null : golden.findById(evaluation.getGoldenItemId()).orElse(null);
      if (evaluation.getAnswer() == null) answerGoldenQuestion(evaluation, crateId);
      EvaluationSnapshot snapshot = MAPPER.readValue(evaluation.getSnapshotJson(), EvaluationSnapshot.class);
      Map<String, Object> details = new LinkedHashMap<>();
      List<String> errors = new ArrayList<>();
      int succeeded = 0, attempted = 0;
      boolean live = evaluation.getSource() == EvaluationCase.Source.LIVE && evaluation.getQuestion() != null;
      boolean tracking = config.isTrackUnanswered() && live;

      // Nothing was retrieved: the knowledge base had no answer. Scores would say nothing here, so only note the gap and its topic.
      if (tracking && snapshot.chunks().isEmpty()) {
        evaluation.answerStatus("UNANSWERED", "no-sources");
        details.put("gap", "no-sources");
        runDimension("topic", crateId, errors, () -> topic(evaluation, true, true));
        if (!errors.isEmpty()) details.put("errors", errors);
        evaluation.done(judge.model(crateId), MAPPER.writeValueAsString(details), errors.isEmpty() ? null : String.join("; ", errors));
        emit(evaluation, crateId, source);
        finish(evaluation, config);
        return;
      }

      if (config.isContextEnabled()) { attempted++; if (runDimension("context", crateId, errors, () -> context(evaluation, config, snapshot, item, details))) succeeded++; }
      if (config.isFaithfulnessEnabled()) { attempted++; if (runDimension("faithfulness", crateId, errors, () -> faithfulness(evaluation, snapshot, details))) succeeded++; }
      if (config.isAnswerRelevanceEnabled()) { attempted++; if (runDimension("answer_relevance", crateId, errors, () -> answerRelevance(evaluation, details))) succeeded++; }
      if (config.isCompletenessEnabled()) { attempted++; if (runDimension("completeness", crateId, errors, () -> completeness(evaluation, details))) succeeded++; }
      if (item != null && item.getReferenceAnswer() != null && !item.getReferenceAnswer().isBlank()) {
        attempted++;
        if (runDimension("reference_recall", crateId, errors, () -> referenceRecall(evaluation, item, details))) succeeded++;
      }
      // Answer status, topic and tags describe the question; they do not count as scored dimensions.
      if (tracking) runDimension("answer_status", crateId, errors, () -> answerStatus(evaluation, details));
      // A question that was not answered always gets a topic, even if topics are not stored in general.
      final boolean wantTopic = config.isStoreTopic() || evaluation.isGap(), wantTags = config.isStoreTags() || evaluation.isGap();
      if (live && (wantTopic || wantTags)) runDimension("topic", crateId, errors, () -> topic(evaluation, wantTopic, wantTags));
      if (errors.size() > 0) details.put("errors", errors);
      String detailsJson = MAPPER.writeValueAsString(details);
      if (attempted > 0 && succeeded == 0) {
        evaluation.failed(String.join("; ", errors));
        finishFailed(evaluation, config, source);
      } else {
        evaluation.done(judge.model(crateId), detailsJson, errors.isEmpty() ? null : String.join("; ", errors));
        emit(evaluation, crateId, source);
        finish(evaluation, config);
      }
    } finally {
      metrics.time("evaluation.duration", crateId, System.nanoTime() - started, "source", source);
    }
  }

  // ------------------------------------------------------------------ dimensions

  @FunctionalInterface
  private interface Dimension { void run() throws Exception; }

  private boolean runDimension(String name, UUID crateId, List<String> errors, Dimension dimension) {
    try {
      dimension.run();
      return true;
    } catch (Exception e) {
      // Never log question, chunk or answer text; the judge's message is about its reply, not the content.
      log.warn("Evaluation dimension {} failed. crateId={}: {}", name, crateId, e.getMessage());
      metrics.count("evaluation.judge.failures", crateId, "dimension", name);
      errors.add(name + ": " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
      return false;
    }
  }

  /** Asks whether the answer gave the requested information; only question and answer are sent. */
  private void answerStatus(EvaluationCase evaluation, Map<String, Object> details) throws Exception {
    var verdict = JudgeProtocol.askVerdict(judge, evaluation.getCrateId(), JudgeProtocol.ANSWER_STATUS,
        JudgeProtocol.coverageInput("QUESTION", evaluation.getQuestion(), evaluation.getAnswer()), 0, 2);
    switch (verdict.score()) {
      case 2 -> evaluation.answerStatus("ANSWERED", null);
      case 1 -> evaluation.answerStatus("PARTIAL", "partial");
      default -> evaluation.answerStatus("UNANSWERED", "not-in-sources");
    }
    details.put("answer_status", Map.of("score", verdict.score(), "reasoning", verdict.reasoning()));
  }

  private void topic(EvaluationCase evaluation, boolean wantTopic, boolean wantTags) throws Exception {
    var result = JudgeProtocol.askTopicTags(judge, evaluation.getCrateId(), evaluation.getQuestion(), wantTopic, wantTags);
    evaluation.topic(result.topic(), result.tags().isEmpty() ? null : MAPPER.writeValueAsString(result.tags()));
  }

  private void context(EvaluationCase evaluation, EvaluationSettings config, EvaluationSnapshot snapshot,
      EvaluationGoldenItem item, Map<String, Object> details) throws Exception {
    UUID crateId = evaluation.getCrateId();
    String question = evaluation.getQuestion();
    int k = Math.min(config.getTopK(), snapshot.chunks().size());
    if (k == 0) throw new IllegalStateException("No chunks were delivered");
    List<String> expected = item == null ? List.of() : readUris(item.getRelevantUris());
    List<Map<String, Object>> graded = new ArrayList<>();
    List<Integer> grades = new ArrayList<>();
    Map<UUID, Integer> judged = new LinkedHashMap<>();

    if (!expected.isEmpty()) {
      // Ground truth: a chunk is relevant when it belongs to one of the expected documents.
      Set<String> found = new LinkedHashSet<>();
      for (int i = 0; i < k; i++) {
        var chunk = snapshot.chunks().get(i);
        String match = matchingEntry(chunk.uri(), expected);
        if (match != null) found.add(match);
        int grade = match == null ? 0 : RankingMetrics.MAX_GRADE;
        grades.add(grade);
        graded.add(entry(chunk.chunkId(), grade, match == null ? "Not an expected document" : "Expected document " + match, false));
      }
      int total = expected.size();
      List<Integer> ideal = Collections.nCopies(total, RankingMetrics.MAX_GRADE);
      evaluation.context(k, RankingMetrics.precisionAtK(grades, k),
          Math.min(1.0, found.size() / (double) total), RankingMetrics.reciprocalRank(grades, k),
          RankingMetrics.averagePrecision(grades, k, total), RankingMetrics.ndcg(grades, k, ideal));
      details.put("context", Map.of("groundTruth", "golden", "expectedDocuments", total, "foundDocuments", found.size(), "chunks", graded));
      return;
    }

    for (int i = 0; i < k; i++) {
      var chunk = snapshot.chunks().get(i);
      var verdict = JudgeProtocol.askVerdict(judge, crateId, JudgeProtocol.CHUNK_RELEVANCE,
          JudgeProtocol.chunkRelevanceInput(question, chunk.content()), 0, RankingMetrics.MAX_GRADE);
      grades.add(verdict.score());
      judged.put(chunk.chunkId(), verdict.score());
      graded.add(entry(chunk.chunkId(), verdict.score(), verdict.reasoning(), false));
    }
    // Candidate pool: the same search, further down. Judged once, it defines how many relevant chunks exist.
    if (config.getPoolSize() > k) {
      var results = index.search(new SearchIndex.SearchRequest(crateId, question, config.getPoolSize(), null, "chunk",
          snapshot.mode() == null || snapshot.mode().isBlank() ? null : snapshot.mode()));
      for (var hit : results.hits()) {
        if (judged.containsKey(hit.id())) continue;
        String text = hit.content() != null ? hit.content() : hit.snippet();
        if (text == null || text.isBlank()) continue;
        var verdict = JudgeProtocol.askVerdict(judge, crateId, JudgeProtocol.CHUNK_RELEVANCE,
            JudgeProtocol.chunkRelevanceInput(question, text), 0, RankingMetrics.MAX_GRADE);
        judged.put(hit.id(), verdict.score());
        graded.add(entry(hit.id(), verdict.score(), verdict.reasoning(), true));
      }
    }
    List<Integer> all = new ArrayList<>(judged.values());
    int totalRelevant = RankingMetrics.relevantCount(all);
    evaluation.context(k, RankingMetrics.precisionAtK(grades, k), RankingMetrics.recallAtK(grades, k, totalRelevant),
        RankingMetrics.reciprocalRank(grades, k), RankingMetrics.averagePrecision(grades, k, totalRelevant), RankingMetrics.ndcg(grades, k, all));
    details.put("context", Map.of("groundTruth", "judge", "judgedChunks", judged.size(), "relevantChunks", totalRelevant, "chunks", graded));
  }

  private void faithfulness(EvaluationCase evaluation, EvaluationSnapshot snapshot, Map<String, Object> details) throws Exception {
    UUID crateId = evaluation.getCrateId();
    JsonNode split = JudgeProtocol.askJson(judge, crateId, JudgeProtocol.SPLIT_STATEMENTS, JudgeProtocol.textInput("TEXT", evaluation.getAnswer()));
    List<String> statements = strings(split.path("statements"), MAX_STATEMENTS);
    if (statements.isEmpty()) { details.put("faithfulness", Map.of("statements", List.of())); return; }
    StringBuilder context = new StringBuilder();
    for (int i = 0; i < snapshot.chunks().size(); i++)
      context.append("[SOURCE ").append(i + 1).append("]\n").append(snapshot.chunks().get(i).content()).append("\n[END SOURCE ").append(i + 1).append("]\n");
    List<Map<String, Object>> checked = new ArrayList<>();
    int supported = 0;
    for (String statement : statements) {
      var verdict = JudgeProtocol.askVerdict(judge, crateId, JudgeProtocol.STATEMENT_SUPPORT,
          JudgeProtocol.statementSupportInput(statement, context.toString()), 0, 1);
      if (verdict.score() == 1) supported++;
      checked.add(Map.of("statement", statement, "supported", verdict.score() == 1, "reasoning", verdict.reasoning()));
    }
    evaluation.faithfulness(supported / (double) statements.size(), statements.size(), supported);
    details.put("faithfulness", Map.of("statements", checked));
  }

  private void answerRelevance(EvaluationCase evaluation, Map<String, Object> details) throws Exception {
    UUID crateId = evaluation.getCrateId();
    JsonNode generated = JudgeProtocol.askJson(judge, crateId, JudgeProtocol.QUESTIONS_FROM_ANSWER, JudgeProtocol.textInput("ANSWER", evaluation.getAnswer()));
    List<String> questions = strings(generated.path("questions"), GENERATED_QUESTIONS);
    if (questions.isEmpty()) throw new IllegalStateException("The judge generated no questions");
    List<Double> similarities = new ArrayList<>();
    List<Map<String, Object>> rows = new ArrayList<>();
    String method;
    if (embeddings.available()) {
      method = "embedding";
      List<String> texts = new ArrayList<>();
      texts.add(evaluation.getQuestion());
      texts.addAll(questions);
      List<float[]> vectors = embeddings.embedQueries(texts);
      for (int i = 0; i < questions.size(); i++) {
        double similarity = Math.max(0, Math.min(1, RankingMetrics.cosine(vectors.get(0), vectors.get(i + 1))));
        similarities.add(similarity);
        rows.add(Map.of("question", questions.get(i), "similarity", similarity));
      }
    } else {
      method = "judge";
      for (String generatedQuestion : questions) {
        var verdict = JudgeProtocol.askVerdict(judge, crateId, JudgeProtocol.QUESTION_SIMILARITY,
            JudgeProtocol.similarityInput(evaluation.getQuestion(), generatedQuestion), 0, 3);
        double similarity = verdict.score() / 3.0;
        similarities.add(similarity);
        rows.add(Map.of("question", generatedQuestion, "similarity", similarity, "reasoning", verdict.reasoning()));
      }
    }
    evaluation.answerRelevance(RankingMetrics.mean(similarities));
    details.put("answerRelevance", Map.of("method", method, "questions", rows));
  }

  private void completeness(EvaluationCase evaluation, Map<String, Object> details) throws Exception {
    UUID crateId = evaluation.getCrateId();
    JsonNode split = JudgeProtocol.askJson(judge, crateId, JudgeProtocol.SPLIT_SUBQUESTIONS, JudgeProtocol.textInput("QUESTION", evaluation.getQuestion()));
    List<String[]> subQuestions = new ArrayList<>();
    for (JsonNode node : split.path("sub_questions")) {
      String text = node.path("text").asText("").strip();
      if (!text.isEmpty() && subQuestions.size() < MAX_SUB_QUESTIONS) subQuestions.add(new String[] {text, importance(node.path("importance").asText(""))});
    }
    if (subQuestions.isEmpty()) throw new IllegalStateException("The judge found no sub-questions");
    double weighted = 0, total = 0;
    List<Map<String, Object>> rows = new ArrayList<>();
    for (String[] sub : subQuestions) {
      var verdict = JudgeProtocol.askVerdict(judge, crateId, JudgeProtocol.SUBQUESTION_COVERAGE,
          JudgeProtocol.coverageInput("SUB-QUESTION", sub[0], evaluation.getAnswer()), 0, 2);
      double weight = weight(sub[1]);
      weighted += weight * (verdict.score() / 2.0);
      total += weight;
      rows.add(Map.of("subQuestion", sub[0], "importance", sub[1], "score", verdict.score(), "reasoning", verdict.reasoning()));
    }
    evaluation.completeness(weighted / total);
    details.put("completeness", Map.of("subQuestions", rows));
  }

  private void referenceRecall(EvaluationCase evaluation, EvaluationGoldenItem item, Map<String, Object> details) throws Exception {
    UUID crateId = evaluation.getCrateId();
    JsonNode split = JudgeProtocol.askJson(judge, crateId, JudgeProtocol.SPLIT_STATEMENTS, JudgeProtocol.textInput("TEXT", item.getReferenceAnswer()));
    List<String> statements = strings(split.path("statements"), MAX_STATEMENTS);
    if (statements.isEmpty()) throw new IllegalStateException("The reference answer has no statements");
    int found = 0;
    List<Map<String, Object>> rows = new ArrayList<>();
    for (String statement : statements) {
      var verdict = JudgeProtocol.askVerdict(judge, crateId, JudgeProtocol.REFERENCE_COVERAGE,
          JudgeProtocol.coverageInput("REFERENCE STATEMENT", statement, evaluation.getAnswer()), 0, 1);
      if (verdict.score() == 1) found++;
      rows.add(Map.of("statement", statement, "contained", verdict.score() == 1, "reasoning", verdict.reasoning()));
    }
    evaluation.referenceRecall(found / (double) statements.size());
    details.put("referenceRecall", Map.of("statements", rows));
  }

  // ------------------------------------------------------------------ golden questions

  /** A golden case has no answer yet: the worker asks the question through the normal answer path. */
  private void answerGoldenQuestion(EvaluationCase evaluation, UUID crateId) throws Exception {
    if (answers == null) throw new IllegalStateException("Answer generation is not available");
    try (var ignored = EvaluationCollector.suppress()) {
      var prepared = answers.prepare(new AnswerService.Request(crateId, evaluation.getQuestion(), null, "chunk", null, null, List.of()));
      var result = answers.generate(prepared);
      evaluation.answered(result.text(), MAPPER.writeValueAsString(EvaluationCollector.snapshot(prepared)));
      cases.save(evaluation);
    }
  }

  // ------------------------------------------------------------------ results

  private void emit(EvaluationCase c, UUID crateId, String source) {
    summary("evaluation.context.precision_at_k", crateId, source, c.getPrecisionAtK());
    summary("evaluation.context.recall_at_k", crateId, source, c.getRecallAtK());
    summary("evaluation.context.mrr", crateId, source, c.getMrr());
    summary("evaluation.context.map", crateId, source, c.getAveragePrecision());
    summary("evaluation.context.ndcg", crateId, source, c.getNdcg());
    summary("evaluation.faithfulness", crateId, source, c.getFaithfulness());
    summary("evaluation.answer_relevance", crateId, source, c.getAnswerRelevance());
    summary("evaluation.completeness", crateId, source, c.getCompleteness());
    summary("evaluation.reference_recall", crateId, source, c.getReferenceRecall());
    metrics.count("evaluation.cases", crateId, "outcome", "done", "source", source);
  }

  private void summary(String name, UUID crateId, String source, Double value) {
    if (value != null) metrics.summary(name, crateId, value, "source", source);
  }

  private void finish(EvaluationCase evaluation, EvaluationSettings config) {
    if (!config.isLogQuestions()) evaluation.scrub();
    cases.save(evaluation);
  }

  private void finishFailed(EvaluationCase evaluation, EvaluationSettings config, String source) {
    metrics.count("evaluation.cases", evaluation.getCrateId(), "outcome", "failed", "source", source);
    if (!config.isLogQuestions()) evaluation.scrub();
    cases.save(evaluation);
  }

  // ------------------------------------------------------------------ helpers

  private static Map<String, Object> entry(UUID chunkId, int grade, String reasoning, boolean pool) {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("chunkId", chunkId.toString());
    row.put("grade", grade);
    row.put("pool", pool);
    row.put("reasoning", reasoning == null ? "" : reasoning);
    return row;
  }

  static List<String> strings(JsonNode array, int max) {
    List<String> out = new ArrayList<>();
    if (array.isArray()) for (JsonNode node : array) {
      String text = node.asText("").strip();
      if (!text.isEmpty() && out.size() < max) out.add(text);
    }
    return out;
  }

  static String importance(String raw) {
    String value = raw.strip().toLowerCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
    return switch (value) { case BACKGROUND -> BACKGROUND; case FOLLOW_UP, "followup" -> FOLLOW_UP; default -> CORE; };
  }

  static double weight(String importance) {
    return switch (importance) { case BACKGROUND -> 0.5; case FOLLOW_UP -> 0.25; default -> 1.0; };
  }

  /** The first expected entry that is part of the chunk's source URI (case-insensitive), or {@code null}. */
  static String matchingEntry(String uri, List<String> expected) {
    if (uri == null) return null;
    String lower = uri.toLowerCase(Locale.ROOT);
    for (String entry : expected) if (lower.contains(entry.toLowerCase(Locale.ROOT))) return entry;
    return null;
  }

  static List<String> readUris(String json) {
    if (json == null || json.isBlank()) return List.of();
    try {
      List<String> out = new ArrayList<>();
      for (JsonNode node : MAPPER.readTree(json)) if (!node.asText("").isBlank()) out.add(node.asText().strip());
      return out;
    } catch (Exception e) {
      return List.of();
    }
  }
}
