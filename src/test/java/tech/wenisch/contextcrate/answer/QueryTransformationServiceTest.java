package tech.wenisch.contextcrate.answer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tech.wenisch.contextcrate.metrics.CrateMetrics;

class QueryTransformationServiceTest {
  private final UUID crate = UUID.randomUUID();
  private final AnswerGenerationProvider provider = mock(AnswerGenerationProvider.class);
  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
  private final QueryTransformationService service = new QueryTransformationService(provider);

  QueryTransformationServiceTest() { service.setMetrics(new CrateMetrics(registry, null, 10)); }

  @Test
  void rewriteSearchesOnlyTheRewrittenQuery() throws Exception {
    when(provider.complete(any())).thenReturn("How do I back up the PostgreSQL database of ContextCrate?");
    var plan = service.plan(crate, "backup how", List.of(), "rewrite");
    assertThat(plan.queries()).containsExactly("How do I back up the PostgreSQL database of ContextCrate?");
    assertThat(plan.applied()).containsExactly("rewrite");
  }

  @Test
  void stepBackSearchesTheQuestionAndTheGeneralisedQuestion() throws Exception {
    when(provider.complete(any())).thenReturn("\"How does ContextCrate handle errors?\"");
    var plan = service.plan(crate, "What does error E1042 mean?", List.of(), "step-back");
    assertThat(plan.queries()).containsExactly("What does error E1042 mean?", "How does ContextCrate handle errors?");
  }

  @Test
  void subQueriesAreSearchedInsteadOfTheQuestionAndCappedAtFour() throws Exception {
    when(provider.complete(any())).thenReturn("1. How to install X?\n2) How to configure Y?\n- Third\n* Fourth\nFifth");
    var plan = service.plan(crate, "Install X and configure Y", List.of(), "sub-queries");
    assertThat(plan.queries()).containsExactly("How to install X?", "How to configure Y?", "Third", "Fourth");
  }

  @Test
  void strategiesRunInFixedOrderAndBuildOnEachOther() throws Exception {
    when(provider.complete(any())).thenReturn("rewritten question", "general question", "part one\npart two");
    // Selection order is irrelevant: the service always applies rewrite, step-back, sub-queries.
    var plan = service.plan(crate, "vague", List.of(), "rewrite,step-back,sub-queries");
    assertThat(plan.applied()).containsExactly("rewrite", "step-back", "sub-queries");
    assertThat(plan.queries()).containsExactly("rewritten question", "general question", "part one", "part two");
    var prompts = ArgumentCaptor.forClass(List.class);
    verify(provider, times(3)).complete(prompts.capture());
    assertThat(prompts.getAllValues().get(1).toString()).contains("rewritten question");
    assertThat(prompts.getAllValues().get(2).toString()).contains("rewritten question");
  }

  @Test
  void historyIsOnlyGivenToRewrite() throws Exception {
    when(provider.complete(any())).thenReturn("a", "b");
    var history = List.of(new AnswerService.HistoryMessage("user", "Tell me about backups"));
    service.plan(crate, "and restores?", history, "rewrite,step-back");
    var prompts = ArgumentCaptor.forClass(List.class);
    verify(provider, times(2)).complete(prompts.capture());
    assertThat(prompts.getAllValues().get(0).toString()).contains("Tell me about backups", "UNTRUSTED");
    assertThat(prompts.getAllValues().get(1).toString()).doesNotContain("Tell me about backups");
  }

  @Test
  void aFailingStepIsSkippedAndTheOthersStillRun() throws Exception {
    when(provider.complete(any())).thenThrow(new IllegalStateException("offline")).thenReturn("general");
    var plan = service.plan(crate, "question", List.of(), "rewrite,step-back");
    assertThat(plan.applied()).containsExactly("step-back");
    assertThat(plan.queries()).containsExactly("question", "general");
    assertThat(registry.get("contextcrate.answer.query_transformation").tag("strategy", "rewrite").tag("outcome", "failed").counter().count()).isEqualTo(1);
  }

  @Test
  void everythingFailingFallsBackToTheOriginalQuestion() throws Exception {
    when(provider.complete(any())).thenReturn("   ");
    var plan = service.plan(crate, "question", List.of(), "rewrite,sub-queries");
    assertThat(plan.applied()).isEmpty();
    assertThat(plan.queries()).containsExactly("question");
  }

  @Test
  void queriesAreDeduplicatedCaseInsensitivelyAndCapped() throws Exception {
    when(provider.complete(any())).thenReturn("Question", "a\nb\nc\nd");
    var plan = service.plan(crate, "question", List.of(), "step-back,sub-queries");
    assertThat(plan.queries()).containsExactly("question", "a", "b", "c", "d");
    assertThat(plan.queries().size()).isLessThanOrEqualTo(QueryTransformationService.MAX_QUERIES);
  }

  @Test
  void autoAppliesTheStrategiesTheModelChooses() throws Exception {
    when(provider.complete(any())).thenReturn("Sure! {\"strategies\": [\"sub-queries\", \"nonsense\", \"rewrite\"]}", "better", "x\ny");
    var plan = service.plan(crate, "Install X and configure Y", List.of(), "auto");
    assertThat(plan.applied()).containsExactly("rewrite", "sub-queries");
    assertThat(plan.queries()).containsExactly("better", "x", "y");
  }

  @Test
  void autoWithGarbageOrNoStrategiesLeavesTheQuestionAlone() throws Exception {
    when(provider.complete(any())).thenReturn("no idea", "{\"strategies\": []}");
    assertThat(service.plan(crate, "q", List.of(), "auto").queries()).containsExactly("q");
    assertThat(service.plan(crate, "q", List.of(), "auto").applied()).isEmpty();
    verify(provider, times(2)).complete(any());
  }

  @Test
  void normalizeValidatesAndOrdersSelections() {
    assertThat(QueryTransformationService.normalize(List.of("sub-queries", "rewrite"))).isEqualTo("rewrite,sub-queries");
    assertThat(QueryTransformationService.normalize(List.of("rewrite,step-back"))).isEqualTo("rewrite,step-back");
    assertThat(QueryTransformationService.normalize(List.of("rewrite", "auto"))).isEqualTo("auto");
    assertThatThrownBy(() -> QueryTransformationService.normalize(List.of("bogus"))).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> QueryTransformationService.normalize(List.of(" "))).isInstanceOf(IllegalArgumentException.class);
  }
}
