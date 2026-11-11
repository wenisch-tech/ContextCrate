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
  private final EvaluationAdminService admin = new EvaluationAdminService(cases, golden, settings, collector, mock(AuditLogRepository.class));

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
    when(cases.findByCrateIdAndStatusAndCreatedAtAfter(eq(crateId), eq(EvaluationCase.Status.DONE), any())).thenReturn(List.of(a, b, g));

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
    when(cases.findByCrateIdOrderByCreatedAtDesc(eq(crateId), any())).thenReturn(List.of(logged));

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
    when(cases.findByCrateIdAndStatusAndCreatedAtBetweenOrderByCreatedAtDesc(eq(crateId), eq(EvaluationCase.Status.DONE), any(), any(), any()))
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
  void theReportIsMarkedTruncatedWhenTooManyCasesMatch() {
    var many = new java.util.ArrayList<EvaluationCase>();
    for (int i = 0; i <= EvaluationAdminService.MAX_REPORT_CASES; i++) many.add(done(EvaluationCase.Source.LIVE, 1.0, 1.0));
    when(cases.findByCrateIdAndStatusAndCreatedAtBetweenOrderByCreatedAtDesc(eq(crateId), any(), any(), any(), any())).thenReturn(many);

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
        null, null, null, null, null, true, false));

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
    assertThatThrownBy(() -> api.report(crateId, 7)).isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(() -> api.cases(crateId, 10)).isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(() -> api.update(crateId, new EvaluationSettingsService.Request(true, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null))).isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(() -> api.run(crateId)).isInstanceOf(AccessDeniedException.class);
    verifyNoInteractions(cases, golden, settingsRepository);
  }

  @Test
  void anOwnerSeesScoresAndTheTextOfLoggedQuestions() {
    var access = mock(CrateAccessService.class);
    when(access.currentUser()).thenReturn(mock(AppUser.class));
    when(cases.findByCrateIdOrderByCreatedAtDesc(eq(crateId), any())).thenReturn(List.of(done(EvaluationCase.Source.LIVE, 1.0, 1.0)));
    var api = new EvaluationApiController(access, mock(CrateRepository.class), settings, admin, mock(AuditLogRepository.class));

    var rows = api.cases(crateId, 10);

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
}
