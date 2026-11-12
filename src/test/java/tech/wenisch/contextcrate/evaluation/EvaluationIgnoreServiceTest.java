package tech.wenisch.contextcrate.evaluation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tech.wenisch.contextcrate.domain.AuditLog;
import tech.wenisch.contextcrate.domain.EvaluationCase;
import tech.wenisch.contextcrate.domain.EvaluationIgnoreRule;
import tech.wenisch.contextcrate.repository.AuditLogRepository;
import tech.wenisch.contextcrate.repository.EvaluationCaseRepository;
import tech.wenisch.contextcrate.repository.EvaluationIgnoreRuleRepository;

class EvaluationIgnoreServiceTest {
  private final UUID crateId = UUID.randomUUID();
  private final List<EvaluationIgnoreRule> stored = new ArrayList<>();
  private final EvaluationIgnoreRuleRepository rules = mock(EvaluationIgnoreRuleRepository.class);
  private final EvaluationCaseRepository cases = mock(EvaluationCaseRepository.class);
  private final AuditLogRepository audits = mock(AuditLogRepository.class);
  private final EvaluationIgnoreService service = new EvaluationIgnoreService(rules, cases, audits);

  EvaluationIgnoreServiceTest() {
    when(rules.findByCrateIdOrderByCreatedAtAsc(crateId)).thenAnswer(i -> List.copyOf(stored));
    when(rules.save(any())).thenAnswer(i -> { stored.add(i.getArgument(0)); return i.getArgument(0); });
    when(rules.findById(any())).thenAnswer(i -> stored.stream().filter(r -> r.getId().equals(i.getArgument(0))).findFirst());
    doAnswer(i -> { stored.remove(i.getArgument(0)); return null; }).when(rules).delete(any());
    when(cases.countIgnored(crateId)).thenReturn(List.of());
  }

  private EvaluationCase done(String question, String topic, String tagsJson) {
    var c = new EvaluationCase(UUID.randomUUID(), crateId, EvaluationCase.Source.LIVE, null, question, "answer", "{}");
    c.topic(topic, tagsJson);
    c.done("judge", null, null);
    return c;
  }

  @Test
  void questionsMatchIgnoringCaseSpacingAndTrailingPunctuation() {
    service.add(crateId, "owner@example.test", "question", "What is 1+1?", false);

    assertThat(service.matchQuestion(crateId, "  what   is 1+1 ")).isPresent();
    assertThat(service.matchQuestion(crateId, "WHAT IS 1+1!")).isPresent();
    assertThat(service.matchQuestion(crateId, "what is 1+1 and 2+2")).isEmpty(); // exact, not contains
    assertThat(service.matchQuestion(crateId, null)).isEmpty();
    assertThat(service.matchQuestion(UUID.randomUUID(), "what is 1+1")).isEmpty();
  }

  @Test
  void containsRulesMatchPartsAndTagRulesAreExact() {
    service.add(crateId, "o", "question", "health check", true);
    service.add(crateId, "o", "topic", "Simple arithmetic", false);
    service.add(crateId, "o", "tag", "Monitoring", true); // "contains" does not apply to tags

    assertThat(service.matchQuestion(crateId, "LiteLLM health check ping")).isPresent();
    assertThat(service.matchTopicOrTags(crateId, "simple arithmetic.", null)).isPresent();
    assertThat(service.matchTopicOrTags(crateId, "Simple arithmetic and more", null)).isEmpty();
    assertThat(service.matchTopicOrTags(crateId, "Other", "[\"math\",\"monitoring\"]")).isPresent();
    assertThat(service.matchTopicOrTags(crateId, "Other", "[\"monitoring-tools\"]")).isEmpty();
    assertThat(stored.get(2).isContainsMatch()).isFalse();
    // a question rule never matches a topic, a topic rule never matches a question
    assertThat(service.matchQuestion(crateId, "simple arithmetic")).isEmpty();
  }

  @Test
  void rulesAreValidated() {
    assertThatThrownBy(() -> service.add(crateId, "o", "colour", "x", false)).hasMessageContaining("question, topic or tag");
    assertThatThrownBy(() -> service.add(crateId, "o", "question", "  ", false)).hasMessageContaining("text");
    assertThatThrownBy(() -> service.add(crateId, "o", "question", "?!", false)).hasMessageContaining("text");
    assertThatThrownBy(() -> service.add(crateId, "o", "question", "x".repeat(2001), false)).hasMessageContaining("too long");

    service.add(crateId, "o", "question", "ping", false);
    service.add(crateId, "o", "question", "PING?", false); // same rule once normalized: skipped
    assertThat(stored).hasSize(1);
    service.add(crateId, "o", "question", "ping", true);   // a different mode is a different rule
    assertThat(stored).hasSize(2);

    for (int i = 0; stored.size() < EvaluationIgnoreService.MAX_RULES; i++) service.add(crateId, "o", "tag", "t" + i, false);
    assertThatThrownBy(() -> service.add(crateId, "o", "tag", "one-more", false)).hasMessageContaining("at most");
  }

  @Test
  void addingRulesForAllWordingsOfATopicIgnoresTheClusterOnce() {
    var views = service.addAll(crateId, "o", "topic", List.of("Rotate an API key", "API key rotation", "rotate an api key"), false);

    assertThat(views).hasSize(2); // the third wording is the first one again
    assertThat(stored).extracting(EvaluationIgnoreRule::getPattern).containsExactly("Rotate an API key", "API key rotation");
  }

  @Test
  void newRulesIgnoreExistingCasesButLeaveWaitingOnesToTheWorker() {
    var health = done("what is 1+1", "Simple arithmetic", "[\"math\"]");
    var other = done("how do I deploy", "Deployment", "[\"docker\"]");
    var waiting = new EvaluationCase(UUID.randomUUID(), crateId, EvaluationCase.Source.LIVE, null, "what is 1+1", null, "{}");
    when(cases.findByCrateIdAndIgnoredRuleIdIsNull(crateId)).thenReturn(List.of(health, other, waiting));

    service.add(crateId, "o", "question", "what is 1+1", false);

    assertThat(health.isIgnored()).isTrue();
    assertThat(other.isIgnored()).isFalse();
    assertThat(waiting.isIgnored()).isFalse(); // the worker re-checks the rules before it evaluates
    verify(cases).saveAll(List.of(health));

    var byTag = done("q", "T", "[\"docker\"]");
    when(cases.findByCrateIdAndIgnoredRuleIdIsNull(crateId)).thenReturn(List.of(byTag, other));
    service.add(crateId, "o", "tag", "docker", false);
    assertThat(byTag.isIgnored()).isTrue();
    assertThat(other.isIgnored()).isTrue();
  }

  @Test
  void deletingARuleBringsItsCasesBackAndTheAuditNeverContainsTheText() {
    var view = service.add(crateId, "owner@example.test", "question", "secret health check", false);
    service.delete(crateId, "owner@example.test", view.id());

    verify(cases).clearIgnored(crateId, view.id());
    assertThat(stored).isEmpty();
    var audit = ArgumentCaptor.forClass(AuditLog.class);
    verify(audits, times(2)).save(audit.capture());
    assertThat(audit.getAllValues()).allSatisfy(a -> assertThat(a.toString() + a.getDetails() + a.getSubject()).doesNotContain("secret health check"));
    // another crate's rule cannot be deleted
    var foreign = new EvaluationIgnoreRule(UUID.randomUUID(), UUID.randomUUID(), EvaluationIgnoreRule.Kind.TAG, "x", false, "o");
    stored.add(foreign);
    service.delete(crateId, "o", foreign.getId());
    assertThat(stored).contains(foreign);
  }

  @Test
  void theSummaryListsEveryRuleWithItsTotalAndNothingElse() {
    var a = service.add(crateId, "o", "question", "what is 1+1", false);
    var b = service.add(crateId, "o", "tag", "monitoring", false);
    var from = Instant.now().minusSeconds(3600);
    var to = Instant.now();
    when(cases.countIgnoredBetween(crateId, from, to)).thenReturn(java.util.Collections.singletonList(new Object[] {a.id(), 41L}));

    var summary = service.summary(crateId, from, to);

    assertThat(summary.total()).isEqualTo(41);
    assertThat(summary.rules()).extracting(EvaluationIgnoreService.RuleCount::pattern).containsExactly("what is 1+1", "monitoring");
    assertThat(summary.rules()).extracting(EvaluationIgnoreService.RuleCount::count).containsExactly(41L, 0L);
    assertThat(b.id()).isNotNull();
  }
}
