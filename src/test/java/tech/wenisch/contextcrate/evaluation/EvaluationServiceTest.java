package tech.wenisch.contextcrate.evaluation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.function.BiFunction;
import org.junit.jupiter.api.Test;
import tech.wenisch.contextcrate.domain.EvaluationCase;
import tech.wenisch.contextcrate.domain.EvaluationGoldenItem;
import tech.wenisch.contextcrate.domain.EvaluationSettings;
import tech.wenisch.contextcrate.embedding.EmbeddingProvider;
import tech.wenisch.contextcrate.index.SearchIndex;
import tech.wenisch.contextcrate.repository.EvaluationCaseRepository;
import tech.wenisch.contextcrate.repository.EvaluationGoldenItemRepository;

class EvaluationServiceTest {
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private final UUID crateId = UUID.randomUUID();
  private final EvaluationCaseRepository cases = mock(EvaluationCaseRepository.class);
  private final EvaluationGoldenItemRepository golden = mock(EvaluationGoldenItemRepository.class);
  private final EvaluationSettingsService settingsService = mock(EvaluationSettingsService.class);
  private final SearchIndex index = mock(SearchIndex.class);
  private final EmbeddingProvider embeddings = mock(EmbeddingProvider.class);
  private final List<String[]> calls = new ArrayList<>();
  private BiFunction<String, String, String> script = (system, user) -> "{\"reasoning\":\"r\",\"score\":0}";
  private final JudgeClient judge = new JudgeClient() {
    @Override public boolean available(UUID id) { return true; }
    @Override public String model(UUID id) { return "judge-model"; }
    @Override public String ask(UUID id, String system, String user) {
      calls.add(new String[] {system, user});
      return script.apply(system, user);
    }
  };

  private final EvaluationSettings settings = new EvaluationSettings(crateId);

  private EvaluationService service() {
    when(cases.findById(any())).thenAnswer(i -> Optional.ofNullable(stored));
    when(cases.save(any())).thenAnswer(i -> i.getArgument(0));
    when(settingsService.current(crateId)).thenReturn(settings);
    when(embeddings.available()).thenReturn(false);
    try { when(index.search(any())).thenReturn(new SearchIndex.SearchResults("q", "hybrid", List.of())); } catch (Exception e) { throw new IllegalStateException(e); }
    return new EvaluationService(cases, golden, settingsService, judge, index, embeddings);
  }

  private EvaluationCase stored;

  private void only(boolean context, boolean faithfulness, boolean relevance, boolean completeness, boolean log) {
    settings.update(true, 1.0, 3, 3, context, faithfulness, relevance, completeness, log, 30);
  }

  private EvaluationCase caseOf(String question, String answer, String... chunkTexts) throws Exception {
    List<EvaluationSnapshot.Chunk> chunks = new ArrayList<>();
    for (String text : chunkTexts)
      chunks.add(new EvaluationSnapshot.Chunk(UUID.randomUUID(), UUID.randomUUID(), "T", "git+https://repo@rev/docs/" + text + ".md", text));
    stored = new EvaluationCase(UUID.randomUUID(), crateId, EvaluationCase.Source.LIVE, null, question, answer,
        MAPPER.writeValueAsString(new EvaluationSnapshot("hybrid", List.of(question), 0, chunks)));
    return stored;
  }

  private static String score(int value) { return "{\"reasoning\":\"because\",\"score\":" + value + "}"; }

  @Test
  void gradesEachChunkSeparatelyAndComputesTheRankingMetrics() throws Exception {
    only(true, false, false, false, true);
    script = (system, user) -> user.contains("CHUNK:\nalpha") ? score(3) : user.contains("CHUNK:\nbeta") ? score(0) : score(2);
    var evaluation = caseOf("What is x?", "x is y.", "alpha", "beta", "gamma");

    service().evaluate(crateId, evaluation.getId());

    assertThat(evaluation.getStatus()).isEqualTo(EvaluationCase.Status.DONE);
    assertThat(calls).hasSize(3); // one judge call per chunk, nothing else
    assertThat(evaluation.getK()).isEqualTo(3);
    assertThat(evaluation.getPrecisionAtK()).isCloseTo(2 / 3.0, within(1e-9));
    assertThat(evaluation.getRecallAtK()).isCloseTo(1.0, within(1e-9)); // both relevant chunks of the judged set were found
    assertThat(evaluation.getMrr()).isEqualTo(1.0);
    assertThat(evaluation.getAveragePrecision()).isCloseTo((1 + 2 / 3.0) / 2, within(1e-9));
    assertThat(evaluation.getNdcg()).isCloseTo((7 + 3 / 2.0) / (7 + 3 / (Math.log(3) / Math.log(2))), within(1e-9));
    assertThat(evaluation.getJudgeModel()).isEqualTo("judge-model");
  }

  @Test
  void theCandidatePoolDefinesHowManyRelevantChunksExist() throws Exception {
    settings.update(true, 1.0, 2, 4, true, false, false, false, true, 30);
    var evaluation = caseOf("q", "a", "alpha", "beta");
    var hits = new ArrayList<SearchIndex.SearchHit>();
    // pool: the two delivered chunks plus two more, one of which is relevant
    var snapshot = MAPPER.readValue(evaluation.getSnapshotJson(), EvaluationSnapshot.class);
    for (var chunk : snapshot.chunks()) hits.add(new SearchIndex.SearchHit(chunk.chunkId(), UUID.randomUUID(), UUID.randomUUID(), "chunk", "T", "u", 0, "s", 1f, null, null, null, chunk.content()));
    hits.add(new SearchIndex.SearchHit(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "chunk", "T", "u", 1, "s", 1f, null, null, null, "delta"));
    hits.add(new SearchIndex.SearchHit(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "chunk", "T", "u", 2, "s", 1f, null, null, null, "epsilon"));
    var service = service();
    when(index.search(any())).thenReturn(new SearchIndex.SearchResults("q", "hybrid", hits));
    script = (system, user) -> user.contains("CHUNK:\nalpha") || user.contains("CHUNK:\ndelta") ? score(3) : score(0);

    service.evaluate(crateId, evaluation.getId());

    assertThat(calls).hasSize(4); // two delivered + two extra pool chunks, each judged once
    assertThat(evaluation.getPrecisionAtK()).isCloseTo(0.5, within(1e-9));
    assertThat(evaluation.getRecallAtK()).isCloseTo(0.5, within(1e-9)); // 1 of the 2 relevant chunks in the pool
  }

  @Test
  void faithfulnessIsSupportedStatementsOverAllStatements() throws Exception {
    only(false, true, false, false, true);
    script = (system, user) -> {
      if (system.equals(JudgeProtocol.SPLIT_STATEMENTS)) return "{\"statements\":[\"A is B\",\"C is D\",\"E is F\"]}";
      return user.contains("STATEMENT:\nE is F") ? score(0) : score(1);
    };
    var evaluation = caseOf("q", "A is B. C is D. E is F.", "alpha");

    service().evaluate(crateId, evaluation.getId());

    assertThat(evaluation.getFaithfulness()).isCloseTo(2 / 3.0, within(1e-9));
    assertThat(evaluation.getStatementsTotal()).isEqualTo(3);
    assertThat(evaluation.getStatementsSupported()).isEqualTo(2);
    assertThat(calls).hasSize(4); // one split + one check per statement
    // Each statement is checked against the delivered context only.
    assertThat(calls.get(1)[1]).contains("CONTEXT:", "[SOURCE 1]", "alpha");
  }

  @Test
  void answerRelevanceUsesEmbeddingsWhenAvailable() throws Exception {
    only(false, false, true, false, true);
    script = (system, user) -> "{\"questions\":[\"q1\",\"q2\",\"q3\"]}";
    var service = service();
    when(embeddings.available()).thenReturn(true);
    when(embeddings.embedQueries(any())).thenReturn(List.of(new float[] {1, 0}, new float[] {1, 0}, new float[] {0, 1}, new float[] {1, 1}));
    var evaluation = caseOf("original", "answer", "alpha");

    service.evaluate(crateId, evaluation.getId());

    assertThat(calls).hasSize(1); // only the question generation needs the judge
    assertThat(evaluation.getAnswerRelevance()).isCloseTo((1 + 0 + Math.sqrt(0.5)) / 3, within(1e-6));
  }

  @Test
  void answerRelevanceFallsBackToTheJudgeWithoutEmbeddings() throws Exception {
    only(false, false, true, false, true);
    script = (system, user) -> system.equals(JudgeProtocol.QUESTIONS_FROM_ANSWER) ? "{\"questions\":[\"q1\",\"q2\"]}"
        : user.contains("QUESTION B:\nq1") ? score(3) : score(0);
    var evaluation = caseOf("original", "answer", "alpha");

    service().evaluate(crateId, evaluation.getId());

    assertThat(evaluation.getAnswerRelevance()).isCloseTo(0.5, within(1e-9));
    assertThat(calls).hasSize(3);
  }

  @Test
  void completenessWeightsSubQuestionsByImportance() throws Exception {
    only(false, false, false, true, true);
    script = (system, user) -> {
      if (system.equals(JudgeProtocol.SPLIT_SUBQUESTIONS))
        return "{\"sub_questions\":[{\"text\":\"core one\",\"importance\":\"core\"},{\"text\":\"background one\",\"importance\":\"background\"},{\"text\":\"later one\",\"importance\":\"follow_up\"}]}";
      return user.contains("core one") ? score(2) : user.contains("background one") ? score(1) : score(0);
    };
    var evaluation = caseOf("q", "a", "alpha");

    service().evaluate(crateId, evaluation.getId());

    assertThat(evaluation.getCompleteness()).isCloseTo((1.0 * 1 + 0.5 * 0.5 + 0.25 * 0) / 1.75, within(1e-9));
  }

  @Test
  void goldenItemsUseExpectedDocumentsAndCheckTheReferenceAnswer() throws Exception {
    only(true, false, false, false, true);
    var item = new EvaluationGoldenItem(UUID.randomUUID(), crateId, "q", "Reference fact one. Reference fact two.", "[\"docs/alpha.md\",\"docs/missing.md\"]");
    when(golden.findById(item.getId())).thenReturn(Optional.of(item));
    script = (system, user) -> {
      if (system.equals(JudgeProtocol.SPLIT_STATEMENTS)) return "{\"statements\":[\"one\",\"two\"]}";
      return user.contains("REFERENCE STATEMENT:\none") ? score(1) : score(0);
    };
    var evaluation = caseOf("q", "answer mentions one", "alpha", "beta");
    stored = new EvaluationCase(evaluation.getId(), crateId, EvaluationCase.Source.GOLDEN, item.getId(), "q", "answer mentions one", evaluation.getSnapshotJson());
    settings.update(true, 1.0, 2, 2, true, false, false, false, true, 30);

    service().evaluate(crateId, stored.getId());

    // Ground truth comes from the golden set: no relevance grading calls, only the reference check.
    assertThat(calls).hasSize(3);
    assertThat(stored.getPrecisionAtK()).isCloseTo(0.5, within(1e-9));
    assertThat(stored.getRecallAtK()).isCloseTo(0.5, within(1e-9)); // 1 of 2 expected documents found
    assertThat(stored.getMrr()).isEqualTo(1.0);
    assertThat(stored.getReferenceRecall()).isCloseTo(0.5, within(1e-9));
  }

  @Test
  void everyPromptAsksForOneDimensionWithReasoningBeforeTheScore() throws Exception {
    settings.update(true, 1.0, 1, 1, true, true, true, true, true, 30);
    script = (system, user) -> {
      if (system.equals(JudgeProtocol.SPLIT_STATEMENTS)) return "{\"statements\":[\"s\"]}";
      if (system.equals(JudgeProtocol.QUESTIONS_FROM_ANSWER)) return "{\"questions\":[\"q\"]}";
      if (system.equals(JudgeProtocol.SPLIT_SUBQUESTIONS)) return "{\"sub_questions\":[{\"text\":\"s\",\"importance\":\"core\"}]}";
      return score(1);
    };
    var evaluation = caseOf("question", "answer", "alpha");

    service().evaluate(crateId, evaluation.getId());

    Set<String> systems = new HashSet<>();
    for (String[] call : calls) {
      systems.add(call[0]);
      assertThat(call[0]).contains("untrusted data");
      if (call[0].contains("\"score\"")) assertThat(call[0].indexOf("reasoning")).isLessThan(call[0].indexOf("\"score\""));
    }
    // Relevance, support, similarity-source questions, sub-questions and coverage are all separate prompts.
    assertThat(systems).containsExactlyInAnyOrder(JudgeProtocol.CHUNK_RELEVANCE, JudgeProtocol.SPLIT_STATEMENTS,
        JudgeProtocol.STATEMENT_SUPPORT, JudgeProtocol.QUESTIONS_FROM_ANSWER, JudgeProtocol.QUESTION_SIMILARITY, JudgeProtocol.SPLIT_SUBQUESTIONS, JudgeProtocol.SUBQUESTION_COVERAGE);
    assertThat(JudgeProtocol.CHUNK_RELEVANCE).doesNotContain("supported").doesNotContain("addressed");
    assertThat(JudgeProtocol.STATEMENT_SUPPORT).doesNotContain("relevan").doesNotContain("addressed");
  }

  @Test
  void anUnusableReplyIsAskedAgainOnceThenTheDimensionFailsAlone() throws Exception {
    settings.update(true, 1.0, 1, 1, true, true, false, false, true, 30);
    int[] relevanceCalls = {0};
    script = (system, user) -> {
      if (system.equals(JudgeProtocol.CHUNK_RELEVANCE)) return relevanceCalls[0]++ == 0 ? "I think it is fine" : score(3);
      return "no json at all"; // faithfulness can never succeed
    };
    var evaluation = caseOf("q", "a", "alpha");

    service().evaluate(crateId, evaluation.getId());

    assertThat(relevanceCalls[0]).isEqualTo(2); // retried once and then accepted
    assertThat(evaluation.getStatus()).isEqualTo(EvaluationCase.Status.DONE);
    assertThat(evaluation.getPrecisionAtK()).isEqualTo(1.0);
    assertThat(evaluation.getFaithfulness()).isNull();
    assertThat(evaluation.getError()).contains("faithfulness");
  }

  @Test
  void aCaseWhereEveryDimensionFailsIsMarkedFailed() throws Exception {
    only(true, true, false, false, false);
    script = (system, user) -> "no json";
    var evaluation = caseOf("secret question", "secret answer", "alpha");

    service().evaluate(crateId, evaluation.getId());

    assertThat(evaluation.getStatus()).isEqualTo(EvaluationCase.Status.FAILED);
    assertThat(evaluation.hasText()).isFalse();
  }

  @Test
  void textIsRemovedAfterEvaluationUnlessQuestionsAreLogged() throws Exception {
    only(true, false, false, false, false);
    script = (system, user) -> score(3);
    var evaluation = caseOf("secret question", "secret answer", "alpha");

    service().evaluate(crateId, evaluation.getId());

    assertThat(evaluation.getStatus()).isEqualTo(EvaluationCase.Status.DONE);
    assertThat(evaluation.getQuestion()).isNull();
    assertThat(evaluation.getAnswer()).isNull();
    assertThat(evaluation.getSnapshotJson()).isNull();
    assertThat(evaluation.getDetailsJson()).isNull();
    assertThat(evaluation.getPrecisionAtK()).isEqualTo(1.0); // the scores stay

    only(true, false, false, false, true);
    var logged = caseOf("kept question", "kept answer", "alpha");
    service().evaluate(crateId, logged.getId());
    assertThat(logged.getQuestion()).isEqualTo("kept question");
    assertThat(logged.getDetailsJson()).contains("because");
  }

  @Test
  void aCaseThatIsAlreadyEvaluatedIsNotEvaluatedAgain() throws Exception {
    only(true, false, false, false, true);
    script = (system, user) -> score(3);
    var evaluation = caseOf("q", "a", "alpha");
    var service = service();
    service.evaluate(crateId, evaluation.getId());
    int first = calls.size();

    service.evaluate(crateId, evaluation.getId());

    assertThat(calls).hasSize(first);
  }

  @Test
  void helpersClassifyWeightsAndMatchDocuments() {
    assertThat(EvaluationService.importance("Follow-up")).isEqualTo("follow_up");
    assertThat(EvaluationService.importance("whatever")).isEqualTo("core");
    assertThat(EvaluationService.weight("background")).isEqualTo(0.5);
    assertThat(EvaluationService.matchingEntry("git+https://r@abc/docs/Oncall.md", List.of("docs/oncall.md"))).isEqualTo("docs/oncall.md");
    assertThat(EvaluationService.matchingEntry("git+https://r@abc/other.md", List.of("docs/oncall.md"))).isNull();
    assertThat(EvaluationService.readUris("[\"a\",\" b \",\"\"]")).containsExactly("a", "b");
    assertThat(EvaluationService.readUris("not json")).isEmpty();
  }
}
