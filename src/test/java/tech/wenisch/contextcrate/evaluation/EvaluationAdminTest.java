package tech.wenisch.contextcrate.evaluation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import tech.wenisch.contextcrate.domain.AppUser;
import tech.wenisch.contextcrate.domain.EvaluationCase;
import tech.wenisch.contextcrate.domain.EvaluationGoldenItem;
import tech.wenisch.contextcrate.domain.EvaluationSettings;
import tech.wenisch.contextcrate.domain.SharedModel;
import tech.wenisch.contextcrate.repository.*;
import tech.wenisch.contextcrate.service.CrateAccessService;
import tech.wenisch.contextcrate.web.EvaluationApiController;

class EvaluationAdminTest {
  private final UUID crateId = UUID.randomUUID();
  private final EvaluationCaseRepository cases = mock(EvaluationCaseRepository.class);
  private final EvaluationGoldenItemRepository golden = mock(EvaluationGoldenItemRepository.class);
  private final EvaluationSettingsRepository settingsRepository = mock(EvaluationSettingsRepository.class);
  private final SharedModelRepository sharedModels = mock(SharedModelRepository.class);
  private final EvaluationSettingsService settings = new EvaluationSettingsService(settingsRepository, sharedModels);
  private final EvaluationCollector collector = mock(EvaluationCollector.class);
  private final tech.wenisch.contextcrate.embedding.EmbeddingProvider embeddings = mock(tech.wenisch.contextcrate.embedding.EmbeddingProvider.class);
  private final java.util.List<String> judgeCalls = new java.util.ArrayList<>();
  private boolean judgeAvailable = false;
  private String judgeReply = "{}";
  private final JudgeClient judge = new JudgeClient() {
    @Override public boolean available(UUID id) { return judgeAvailable; }
    @Override public String model(UUID id) { return "judge-model"; }
    @Override public String ask(UUID id, String system, String user) { judgeCalls.add(user); return judgeReply; }
  };
  private final EvaluationIgnoreRuleRepository ignoreRules = mock(EvaluationIgnoreRuleRepository.class);
  private final EvaluationIgnoreService ignoreService = new EvaluationIgnoreService(ignoreRules, cases, mock(AuditLogRepository.class));
  private final EvaluationAdminService admin = new EvaluationAdminService(cases, golden, settings, collector, mock(AuditLogRepository.class),
      new TopicGrouper(embeddings, judge), judge, ignoreService);

  private EvaluationCase done(EvaluationCase.Source source, Double faithfulness, Double precision) {
    var c = new EvaluationCase(UUID.randomUUID(), crateId, source, null, "secret question", "secret answer", "{}");
    c.faithfulness(faithfulness, 4, 3);
    c.context(5, precision, null, 1.0, null, null);
    c.done("judge", "{\"x\":1}", null);
    return c;
  }

  // ---- settings validation

  @Test
  void settingsAreValidated() {
    when(settingsRepository.findById(crateId)).thenReturn(Optional.empty());
    when(settingsRepository.save(any())).thenAnswer(i -> i.getArgument(0));

    var saved = settings.update(crateId, true, 0.25, 5, 20, true, true, true, true, false, 30);
    assertThat(saved.isEnabled()).isTrue();
    assertThat(saved.getSamplingRate()).isEqualTo(0.25);
    assertThatThrownBy(() -> settings.update(crateId, true, 0.0, 5, 20, true, true, true, true, false, 30)).hasMessageContaining("sampling");
    assertThatThrownBy(() -> settings.update(crateId, true, 1.0, 0, 20, true, true, true, true, false, 30)).hasMessageContaining("K");
    assertThatThrownBy(() -> settings.update(crateId, true, 1.0, 10, 5, true, true, true, true, false, 30)).hasMessageContaining("pool");
    assertThatThrownBy(() -> settings.update(crateId, true, 1.0, 5, 20, true, true, true, true, false, 0)).hasMessageContaining("retention");
  }

  @Test
  void aJudgeIsOnlyConfiguredWithAnEndpointAndModelOrASharedJudge() {
    var stored = new EvaluationSettings(crateId);
    when(settingsRepository.findById(crateId)).thenReturn(Optional.of(stored));
    when(settingsRepository.save(any())).thenAnswer(i -> i.getArgument(0));
    assertThat(settings.judge(crateId)).isNull();

    settings.updateJudge(crateId, null, "https://judge.example/v1", "judge-1", "secret-key", 90);

    assertThat(settings.judge(crateId)).isEqualTo(new EvaluationSettingsService.JudgeConfig("https://judge.example/v1", "judge-1", "secret-key", 90));
    // The view reports that a key is stored but never contains it.
    assertThat(settings.view(crateId).judgeApiKeySet()).isTrue();
    assertThat(settings.view(crateId).toString()).doesNotContain("secret-key");
    // A blank key keeps the stored one.
    settings.updateJudge(crateId, null, "https://judge.example/v1", "judge-1", "", 90);
    assertThat(settings.judge(crateId).apiKey()).isEqualTo("secret-key");
    assertThatThrownBy(() -> settings.updateJudge(crateId, null, "ftp://x", "m", null, 90)).hasMessageContaining("http");
    assertThatThrownBy(() -> settings.updateJudge(crateId, null, "https://x", "", null, 90)).hasMessageContaining("model");
    assertThatThrownBy(() -> settings.updateJudge(crateId, null, "https://x", "m", null, 1)).hasMessageContaining("timeout");
  }

  @Test
  void ownersMayOnlyUseSharedJudgesWhenCustomEndpointsAreForbidden() {
    var policy = mock(tech.wenisch.contextcrate.config.RuntimeProviderSettings.class);
    when(policy.allowCustomEndpoints()).thenReturn(false);
    settings.setPolicy(policy);
    var judge = new SharedModel(SharedModel.Kind.JUDGE);
    when(sharedModels.findById(judge.getId())).thenReturn(Optional.of(judge));
    when(settingsRepository.findById(crateId)).thenReturn(Optional.of(new EvaluationSettings(crateId)));
    when(settingsRepository.save(any())).thenAnswer(i -> i.getArgument(0));

    assertThatThrownBy(() -> settings.updateJudge(crateId, null, "https://own.example/v1", "m", "k", 90)).hasMessageContaining("disabled by the administrator");
    // A shared judge is always allowed.
    assertThat(settings.updateJudge(crateId, judge.getId(), null, null, null, 90).getJudgeSharedModelId()).isEqualTo(judge.getId());
  }

  @Test
  void aSharedJudgeMustBeOfKindJudge() {
    var answerModel = new SharedModel(SharedModel.Kind.ANSWER);
    when(sharedModels.findById(answerModel.getId())).thenReturn(Optional.of(answerModel));
    when(settingsRepository.findById(crateId)).thenReturn(Optional.of(new EvaluationSettings(crateId)));

    assertThatThrownBy(() -> settings.updateJudge(crateId, answerModel.getId(), null, null, null, 90)).hasMessageContaining("not a judge");
  }

  // ---- summary and cases

  @Test
  void averagesIgnoreMissingValuesAndSeparateGoldenFromLive() {
    var a = done(EvaluationCase.Source.LIVE, 1.0, 0.5);
    var b = done(EvaluationCase.Source.LIVE, 0.5, null);
    var g = done(EvaluationCase.Source.GOLDEN, 0.0, 1.0);
    when(cases.findByCrateIdAndStatusAndIgnoredRuleIdIsNullAndCreatedAtAfter(eq(crateId), eq(EvaluationCase.Status.DONE), any())).thenReturn(List.of(a, b, g));

    var summary = admin.summary(crateId, 7);

    assertThat(summary.live().cases()).isEqualTo(2);
    assertThat(summary.live().faithfulness()).isEqualTo(0.75);
    assertThat(summary.live().precisionAtK()).isEqualTo(0.5); // the missing value is not counted as zero
    assertThat(summary.live().answerRelevance()).isNull();
    assertThat(summary.golden().cases()).isEqualTo(1);
    assertThat(summary.golden().faithfulness()).isEqualTo(0.0);
    assertThat(summary.trend()).hasSize(1);
  }

  @Test
  void textIsOnlyReturnedWhenAllowedAndStillPresent() {
    var logged = done(EvaluationCase.Source.LIVE, 1.0, 1.0);
    when(cases.findByCrateIdAndIgnoredRuleIdIsNullOrderByCreatedAtDesc(eq(crateId), any())).thenReturn(List.of(logged));

    assertThat(admin.cases(crateId, 50, false).getFirst().text()).isNull();
    assertThat(admin.cases(crateId, 50, true).getFirst().text().question()).isEqualTo("secret question");

    logged.scrub();
    assertThat(admin.cases(crateId, 50, true).getFirst().text()).isNull(); // nothing left to show
    assertThat(admin.cases(crateId, 50, true).getFirst().faithfulness()).isEqualTo(1.0);
  }

  // ---- golden set

  @Test
  void goldenQuestionsNeedAGroundTruthAndAreLimited() {
    when(golden.countByCrateId(crateId)).thenReturn(0L);
    when(golden.save(any())).thenAnswer(i -> i.getArgument(0));

    var added = admin.addGolden(crateId, "admin@example.test", " How do I escalate? ", null, List.of(" docs/oncall.md ", "", "docs/oncall.md"));
    assertThat(added.question()).isEqualTo("How do I escalate?");
    assertThat(added.relevantUris()).containsExactly("docs/oncall.md");
    assertThatThrownBy(() -> admin.addGolden(crateId, "a", " ", "ref", List.of())).hasMessageContaining("question");
    assertThatThrownBy(() -> admin.addGolden(crateId, "a", "q", null, List.of())).hasMessageContaining("expected documents");
    when(golden.countByCrateId(crateId)).thenReturn((long) EvaluationAdminService.MAX_GOLDEN_ITEMS);
    assertThatThrownBy(() -> admin.addGolden(crateId, "a", "q", "ref", List.of())).hasMessageContaining("at most");
  }

  @Test
  void importAcceptsAJsonArrayAndRejectsGarbage() {
    when(golden.countByCrateId(crateId)).thenReturn(0L);
    when(golden.save(any())).thenAnswer(i -> i.getArgument(0));

    assertThat(admin.importGolden(crateId, "a", "[{\"question\":\"q1\",\"relevantUris\":[\"a.md\"]},{\"question\":\"q2\",\"referenceAnswer\":\"ref\"}]")).isEqualTo(2);
    assertThatThrownBy(() -> admin.importGolden(crateId, "a", "{\"not\":\"an array\"}")).hasMessageContaining("array");
    assertThatThrownBy(() -> admin.importGolden(crateId, "a", "nope")).hasMessageContaining("JSON");
  }

  @Test
  void runningTheGoldenSetQueuesOneCasePerQuestionAndNeedsAJudge() throws Exception {
    var item = new EvaluationGoldenItem(UUID.randomUUID(), crateId, "q", "ref", null);
    when(golden.findByCrateIdOrderByCreatedAtAsc(crateId)).thenReturn(List.of(item, item));
    when(settingsRepository.findById(crateId)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> admin.runGolden(crateId, "a")).hasMessageContaining("judge");

    var configured = new EvaluationSettings(crateId);
    configured.updateJudge(null, "https://judge.example/v1", "m", "k", 60);
    when(settingsRepository.findById(crateId)).thenReturn(Optional.of(configured));

    assertThat(admin.runGolden(crateId, "a")).isEqualTo(2);
    verify(collector, times(2)).publish(eq(crateId), any());
  }

  // ---- topics, tags and the report

  private EvaluationCase withTopic(String topic, String tagsJson, double faithfulness, java.time.Instant at) throws Exception {
    var c = done(EvaluationCase.Source.LIVE, faithfulness, 0.5);
    c.topic(topic, tagsJson);
    c.scrub(); // raw text is gone, topic and tags stay
    var field = EvaluationCase.class.getDeclaredField("createdAt");
    field.setAccessible(true);
    field.set(c, at);
    return c;
  }

  @Test
  void theReportGroupsTopicsAndTagsAndKeepsRawTextOut() throws Exception {
    var now = java.time.Instant.now();
    when(cases.findByCrateIdAndStatusAndIgnoredRuleIdIsNullAndCreatedAtBetweenOrderByCreatedAtDesc(eq(crateId), eq(EvaluationCase.Status.DONE), any(), any(), any()))
        .thenReturn(List.of(
            withTopic("How to deploy with Docker", "[\"development\",\"docker\"]", 1.0, now),
            withTopic("how to deploy with docker.", "[\"docker\"]", 0.5, now),
            withTopic("Reset a password", "[\"security\"]", 0.8, now),
            done(EvaluationCase.Source.GOLDEN, 0.9, 0.5)));

    var report = admin.report(crateId, now.minusSeconds(3600), now.plusSeconds(3600), false);

    assertThat(report.questions()).isEqualTo(3);
    assertThat(report.withTopic()).isEqualTo(3);
    assertThat(report.golden().cases()).isEqualTo(1);
    assertThat(report.topTopics().get(0).label()).isEqualTo("How to deploy with Docker");
    assertThat(report.topTopics().get(0).count()).isEqualTo(2);
    assertThat(report.topTopics().get(0).faithfulness()).isEqualTo(0.75);
    assertThat(report.topTags().get(0).label()).isEqualTo("docker");
    assertThat(report.topTags().get(0).count()).isEqualTo(2);
    assertThat(report.latest()).hasSize(3);
    assertThat(report.latest().get(0).tags()).containsExactly("development", "docker");
    assertThat(report.latest().get(0).text()).isNull();
    assertThat(report.truncated()).isFalse();
  }

  @Test
  void paraphrasedTopicsAreMergedByMeaningWhenEmbeddingsExist() throws Exception {
    var now = java.time.Instant.now();
    when(embeddings.available()).thenReturn(true);
    when(embeddings.embedQueries(any())).thenAnswer(i -> ((List<String>) i.getArgument(0)).stream()
        .map(t -> t.toLowerCase().contains("docker") ? new float[] {1f, 0f} : new float[] {0f, 1f}).toList());
    when(cases.findByCrateIdAndStatusAndIgnoredRuleIdIsNullAndCreatedAtBetweenOrderByCreatedAtDesc(eq(crateId), eq(EvaluationCase.Status.DONE), any(), any(), any()))
        .thenReturn(List.of(withTopic("Deploy with Docker", "[]", 1.0, now), withTopic("Docker deployment steps", "[]", 0.5, now),
            withTopic("Docker deployment steps", "[]", 0.5, now), withTopic("Reset a password", "[]", 0.8, now)));

    var report = admin.report(crateId, now.minusSeconds(60), now.plusSeconds(60), false);

    assertThat(report.groupingMethod()).isEqualTo("embedding");
    assertThat(report.topTopics()).hasSize(2);
    assertThat(report.topTopics().get(0).label()).isEqualTo("Docker deployment steps");
    assertThat(report.topTopics().get(0).count()).isEqualTo(3);
    assertThat(report.topTopics().get(0).variants()).containsExactly("Deploy with Docker");
    assertThat(report.topTopics().get(0).faithfulness()).isCloseTo(0.6667, org.assertj.core.data.Offset.offset(0.001));
  }

  @Test
  void theSummaryIsWrittenFromAggregatedNumbersOnlyAndIsCached() throws Exception {
    var now = java.time.Instant.now();
    var secret = withTopic("Reset a password", "[\"security\"]", 0.4, now);
    when(cases.findByCrateIdAndStatusAndIgnoredRuleIdIsNullAndCreatedAtBetweenOrderByCreatedAtDesc(eq(crateId), any(), any(), any(), any())).thenReturn(List.of(secret));
    var report = admin.report(crateId, now.minusSeconds(60), now.plusSeconds(60), true);
    judgeAvailable = true;
    judgeReply = "Sure: {\"overview\":\"  People asked about passwords. \",\"well\":[],\"poorly\":[\"Reset a password: faithfulness 0.40\"],"
        + "\"suggestions\":[\"a\",\"b\",\"c\",\"d\",\"e\",\"f\",\"g\"]}";

    var summary = admin.summarize(crateId, report);
    admin.summarize(crateId, report);

    assertThat(summary.overview()).isEqualTo("People asked about passwords.");
    assertThat(summary.poorly()).containsExactly("Reset a password: faithfulness 0.40");
    assertThat(summary.suggestions()).hasSize(6);
    assertThat(summary.model()).isEqualTo("judge-model");
    assertThat(judgeCalls).hasSize(1); // the second call came from the cache
    assertThat(judgeCalls.get(0)).contains("Reset a password", "faithfulness 0.40").doesNotContain("secret question").doesNotContain("secret answer");
  }

  @Test
  void aSummaryNeedsAJudgeAndData() throws Exception {
    var now = java.time.Instant.now();
    when(cases.findByCrateIdAndStatusAndIgnoredRuleIdIsNullAndCreatedAtBetweenOrderByCreatedAtDesc(eq(crateId), any(), any(), any(), any())).thenReturn(List.of(withTopic("T", "[]", 1.0, now)));
    var report = admin.report(crateId, now.minusSeconds(60), now.plusSeconds(60), false);
    assertThatThrownBy(() -> admin.summarize(crateId, report)).hasMessageContaining("judge");

    judgeAvailable = true;
    when(cases.findByCrateIdAndStatusAndIgnoredRuleIdIsNullAndCreatedAtBetweenOrderByCreatedAtDesc(eq(crateId), any(), eq(now.plusSeconds(3600)), any(), any())).thenReturn(List.of());
    var none = admin.report(crateId, now.plusSeconds(3600), now.plusSeconds(7200), false);
    assertThatThrownBy(() -> admin.summarize(crateId, none)).hasMessageContaining("no evaluated answers");
    judgeReply = "{\"overview\":\"\"}";
    assertThatThrownBy(() -> admin.summarize(crateId, report)).hasMessageContaining("empty");
  }

  @Test
  void theReportIsMarkedTruncatedWhenTooManyCasesMatch() {
    var many = new java.util.ArrayList<EvaluationCase>();
    for (int i = 0; i <= EvaluationAdminService.MAX_REPORT_CASES; i++) many.add(done(EvaluationCase.Source.LIVE, 1.0, 1.0));
    when(cases.findByCrateIdAndStatusAndIgnoredRuleIdIsNullAndCreatedAtBetweenOrderByCreatedAtDesc(eq(crateId), any(), any(), any(), any())).thenReturn(many);

    var report = admin.report(crateId, java.time.Instant.EPOCH, java.time.Instant.now(), false);

    assertThat(report.truncated()).isTrue();
    assertThat(report.questions()).isEqualTo(EvaluationAdminService.MAX_REPORT_CASES);
    assertThat(report.latest()).hasSize(EvaluationAdminService.MAX_REPORT_ROWS);
  }

  @Test
  void topicSwitchesAreStoredThroughTheSettingsApply() {
    var stored = new EvaluationSettings(crateId);
    when(settingsRepository.findById(crateId)).thenReturn(Optional.of(stored));
    when(settingsRepository.save(any())).thenAnswer(i -> i.getArgument(0));

    var view = settings.apply(crateId, new EvaluationSettingsService.Request(null, null, null, null, null, null, null, null, null, null,
        null, null, null, null, null, true, false, null));

    assertThat(view.storeTopic()).isTrue();
    assertThat(view.storeTags()).isFalse();
  }

  // ---- access

  @Test
  void theApiIsForCrateOwnersOnly() {
    var access = mock(CrateAccessService.class);
    when(access.require(crateId, tech.wenisch.contextcrate.domain.CrateMember.Role.OWNER)).thenThrow(new AccessDeniedException("Insufficient crate access"));
    when(access.requireMutable(crateId, tech.wenisch.contextcrate.domain.CrateMember.Role.OWNER)).thenThrow(new AccessDeniedException("Insufficient crate access"));
    var api = new EvaluationApiController(access, mock(CrateRepository.class), settings, admin, mock(AuditLogRepository.class));

    assertThatThrownBy(() -> api.settings(crateId)).isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(() -> api.summary(crateId, 7)).isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(() -> api.report(crateId, 7, false, false, false)).isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(() -> api.cases(crateId, 10, false)).isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(() -> api.update(crateId, new EvaluationSettingsService.Request(true, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null))).isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(() -> api.run(crateId)).isInstanceOf(AccessDeniedException.class);
    verifyNoInteractions(cases, golden, settingsRepository);
  }

  @Test
  void anOwnerSeesScoresAndTheTextOfLoggedQuestions() {
    var access = mock(CrateAccessService.class);
    when(access.currentUser()).thenReturn(mock(AppUser.class));
    when(cases.findByCrateIdAndIgnoredRuleIdIsNullOrderByCreatedAtDesc(eq(crateId), any())).thenReturn(List.of(done(EvaluationCase.Source.LIVE, 1.0, 1.0)));
    var api = new EvaluationApiController(access, mock(CrateRepository.class), settings, admin, mock(AuditLogRepository.class));

    var rows = api.cases(crateId, 10, false);

    assertThat(rows.getFirst().faithfulness()).isEqualTo(1.0);
    assertThat(rows.getFirst().text().question()).isEqualTo("secret question");
  }

  @Test
  void theApiJsonCarriesPlainDataNotJacksonTreeNodes() throws Exception {
    var logged = done(EvaluationCase.Source.LIVE, 1.0, 1.0);
    String json = tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(EvaluationAdminService.view(logged, true));

    // The web layer uses a different Jackson version than the internals; a tree node would be serialised as a bean.
    assertThat(json).doesNotContain("nodeType").doesNotContain("containerNode");
    assertThat(json).contains("\"details\":{\"x\":1}").contains("secret question");
  }

  // ---- unanswered questions in the report

  private EvaluationCase gap(String topic, String tagsJson, String status, String reason, java.time.Instant at) throws Exception {
    var c = withTopic(topic, tagsJson, 0.5, at);
    c.answerStatus(status, reason);
    return c;
  }

  @Test
  void theReportCountsAndClustersQuestionsTheKnowledgeBaseCouldNotAnswer() throws Exception {
    var now = java.time.Instant.now();
    when(embeddings.available()).thenReturn(true);
    when(embeddings.embedQueries(any())).thenAnswer(i -> ((List<String>) i.getArgument(0)).stream()
        .map(t -> t.toLowerCase().contains("key") ? new float[] {1f, 0f} : new float[] {0f, 1f}).toList());
    var answered = withTopic("Reset a password", "[]", 0.9, now);
    answered.answerStatus("ANSWERED", null);
    when(cases.findByCrateIdAndStatusAndIgnoredRuleIdIsNullAndCreatedAtBetweenOrderByCreatedAtDesc(eq(crateId), eq(EvaluationCase.Status.DONE), any(), any(), any()))
        .thenReturn(List.of(
            gap("Rotate an API key", "[\"security\"]", "UNANSWERED", "no-sources", now),
            gap("API key rotation steps", "[\"security\",\"api\"]", "UNANSWERED", "not-in-sources", now.minusSeconds(60)),
            gap("API key rotation steps", "[\"api\"]", "PARTIAL", "partial", now.minusSeconds(120)),
            gap("Export billing data", "[]", "UNANSWERED", "no-sources", now.minusSeconds(180)),
            answered));

    var gaps = admin.report(crateId, now.minusSeconds(3600), now.plusSeconds(3600), false).gaps();

    assertThat(gaps.tracked()).isEqualTo(5);
    assertThat(gaps.unanswered()).isEqualTo(3);
    assertThat(gaps.partial()).isEqualTo(1);
    assertThat(gaps.noSources()).isEqualTo(2);
    assertThat(gaps.notInSources()).isEqualTo(1);
    assertThat(gaps.gaps()).isEqualTo(4);
    assertThat(gaps.share()).isEqualTo(0.8);
    assertThat(gaps.topics()).hasSize(2); // the answered topic is not a gap
    assertThat(gaps.topics().get(0).count()).isEqualTo(3);
    assertThat(gaps.topics().get(0).label()).isEqualTo("API key rotation steps");
    assertThat(gaps.topics().get(0).variants()).containsExactly("Rotate an API key");
    assertThat(gaps.topics().get(0).noSources()).isEqualTo(1);
    assertThat(gaps.topics().get(0).notInSources()).isEqualTo(1);
    assertThat(gaps.topics().get(0).partial()).isEqualTo(1);
    assertThat(gaps.topics().get(0).tags()).containsExactly("api", "security");
    assertThat(gaps.latest()).hasSize(4);
    assertThat(gaps.latest().get(0).topic()).isEqualTo("Rotate an API key");
    assertThat(gaps.latest().get(0).question()).isNull(); // raw text was scrubbed
  }

  @Test
  void aReportWithoutTrackedAnswersHasNoGaps() throws Exception {
    var now = java.time.Instant.now();
    when(cases.findByCrateIdAndStatusAndIgnoredRuleIdIsNullAndCreatedAtBetweenOrderByCreatedAtDesc(eq(crateId), any(), any(), any(), any())).thenReturn(List.of(withTopic("T", "[]", 1.0, now)));

    var gaps = admin.report(crateId, now.minusSeconds(60), now.plusSeconds(60), false).gaps();

    assertThat(gaps.tracked()).isZero();
    assertThat(gaps.gaps()).isZero();
    assertThat(gaps.share()).isZero();
    assertThat(gaps.topics()).isEmpty();
  }

  @Test
  void theGapAnalysisIsWrittenFromClusteredTopicsOnlyAndNeedsGaps() throws Exception {
    var now = java.time.Instant.now();
    var question = gap("Rotate an API key", "[\"security\"]", "UNANSWERED", "no-sources", now);
    when(cases.findByCrateIdAndStatusAndIgnoredRuleIdIsNullAndCreatedAtBetweenOrderByCreatedAtDesc(eq(crateId), any(), any(), any(), any())).thenReturn(List.of(question));
    var report = admin.report(crateId, now.minusSeconds(60), now.plusSeconds(60), true);
    judgeAvailable = true;
    judgeReply = "{\"overview\":\"One topic is missing.\",\"themes\":[\"API keys: 1\"],\"causes\":[\"No page\"],\"actions\":[\"Write a rotation guide\"]}";

    var analysis = admin.summarizeGaps(crateId, report);
    admin.summarizeGaps(crateId, report);

    assertThat(analysis.overview()).isEqualTo("One topic is missing.");
    assertThat(analysis.actions()).containsExactly("Write a rotation guide");
    assertThat(judgeCalls).hasSize(1); // cached
    assertThat(judgeCalls.get(0)).contains("Rotate an API key", "no-sources 1", "security").doesNotContain("secret question");
    assertThat(report.withGapSummary(analysis).gaps().summary()).isEqualTo(analysis);

    when(cases.findByCrateIdAndStatusAndIgnoredRuleIdIsNullAndCreatedAtBetweenOrderByCreatedAtDesc(eq(crateId), any(), any(), any(), any())).thenReturn(List.of());
    var none = admin.report(crateId, now.minusSeconds(60), now.plusSeconds(60), true);
    assertThatThrownBy(() -> admin.summarizeGaps(crateId, none)).hasMessageContaining("no unanswered questions");
  }

  // ---- ignore rules in the list and the report

  @Test
  void theListHidesIgnoredCasesUnlessAskedAndMarksThem() {
    var normal = done(EvaluationCase.Source.LIVE, 1.0, 0.5);
    var health = done(EvaluationCase.Source.LIVE, 1.0, 0.5);
    health.ignoredBy(UUID.randomUUID());
    when(cases.findByCrateIdAndIgnoredRuleIdIsNullOrderByCreatedAtDesc(eq(crateId), any())).thenReturn(List.of(normal));
    when(cases.findByCrateIdOrderByCreatedAtDesc(eq(crateId), any())).thenReturn(List.of(normal, health));

    assertThat(admin.cases(crateId, 50, false)).hasSize(1);
    assertThat(admin.cases(crateId, 50, false, false)).extracting(EvaluationAdminService.CaseView::ignored).containsExactly(false);
    assertThat(admin.cases(crateId, 50, false, true)).extracting(EvaluationAdminService.CaseView::ignored).containsExactly(false, true);
  }

  @Test
  void summaryAndReportOnlyAskForCasesThatAreNotIgnored() throws Exception {
    var now = java.time.Instant.now();
    admin.summary(crateId, 7);
    admin.report(crateId, now.minusSeconds(60), now.plusSeconds(60), false);

    verify(cases).findByCrateIdAndStatusAndIgnoredRuleIdIsNullAndCreatedAtAfter(eq(crateId), eq(EvaluationCase.Status.DONE), any());
    verify(cases).findByCrateIdAndStatusAndIgnoredRuleIdIsNullAndCreatedAtBetweenOrderByCreatedAtDesc(eq(crateId), eq(EvaluationCase.Status.DONE), any(), any(), any());
    verify(cases, never()).findByCrateIdAndStatusAndCreatedAtAfter(any(), any(), any());
  }

  @Test
  void theIgnoredSummaryIsTheRuleAndATotalOnly() {
    var rule = new tech.wenisch.contextcrate.domain.EvaluationIgnoreRule(UUID.randomUUID(), crateId,
        tech.wenisch.contextcrate.domain.EvaluationIgnoreRule.Kind.QUESTION, "what is 1+1", false, "owner");
    when(ignoreRules.findByCrateIdOrderByCreatedAtAsc(crateId)).thenReturn(List.of(rule));
    var now = java.time.Instant.now();
    when(cases.countIgnoredBetween(crateId, now.minusSeconds(60), now)).thenReturn(java.util.Collections.singletonList(new Object[] {rule.getId(), 1440L}));

    var report = admin.ignoredSummary(crateId, now.minusSeconds(60), now);

    assertThat(report.total()).isEqualTo(1440);
    assertThat(report.rules()).containsExactly(new EvaluationIgnoreService.RuleCount("QUESTION", "what is 1+1", false, 1440));
  }
}
