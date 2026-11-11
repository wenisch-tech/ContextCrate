package tech.wenisch.contextcrate.evaluation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tech.wenisch.contextcrate.embedding.EmbeddingProvider;

class TopicGrouperTest {
  private final UUID crateId = UUID.randomUUID();
  private final EmbeddingProvider embeddings = mock(EmbeddingProvider.class);
  private final List<String> asked = new ArrayList<>();
  private boolean judgeAvailable = true;
  private String reply = "{\"groups\":[[0,2],[1]]}";
  private final JudgeClient judge = new JudgeClient() {
    @Override public boolean available(UUID id) { return judgeAvailable; }
    @Override public String model(UUID id) { return "j"; }
    @Override public String ask(UUID id, String system, String user) { asked.add(user); return reply; }
  };
  private final TopicGrouper grouper = new TopicGrouper(embeddings, judge);
  private final List<String> topics = List.of("Deploy with Docker", "Reset a password", "Docker deployment");

  @Test
  void embeddingsMergeSimilarTopicsAndKeepTheMostFrequentAsLeader() throws Exception {
    when(embeddings.available()).thenReturn(true);
    when(embeddings.embedQueries(any())).thenReturn(List.of(new float[] {1, 0}, new float[] {0, 1}, new float[] {0.9f, 0.1f}));

    var result = grouper.group(crateId, topics);

    assertThat(result.method()).isEqualTo("embedding");
    assertThat(result.clusters()).containsExactly(List.of("Deploy with Docker", "Docker deployment"), List.of("Reset a password"));
    assertThat(asked).isEmpty();
  }

  @Test
  void aSimilarityBelowTheThresholdKeepsTopicsApart() throws Exception {
    when(embeddings.available()).thenReturn(true);
    when(embeddings.embedQueries(any())).thenReturn(List.of(new float[] {1, 0}, new float[] {0.6f, 0.8f}));
    assertThat(grouper.group(crateId, List.of("a", "b")).clusters()).hasSize(2);
  }

  @Test
  void theJudgeGroupsWhenThereAreNoEmbeddingsAndItsAnswerIsRepaired() {
    when(embeddings.available()).thenReturn(false);
    reply = "{\"groups\":[[0,2,2],[9],[\"x\"]]}"; // duplicate, unknown and non-numeric entries; index 1 is forgotten

    var result = grouper.group(crateId, topics);

    assertThat(result.method()).isEqualTo("judge");
    assertThat(result.clusters()).containsExactly(List.of("Deploy with Docker", "Docker deployment"), List.of("Reset a password"));
    assertThat(asked.get(0)).contains("0: Deploy with Docker", "1: Reset a password");
  }

  @Test
  void failingEmbeddingsHandOverToTheJudgeAndThenToTheText() throws Exception {
    when(embeddings.available()).thenReturn(true);
    when(embeddings.embedQueries(any())).thenThrow(new IllegalStateException("down"));
    assertThat(grouper.group(crateId, topics).method()).isEqualTo("judge");

    reply = "no json";
    var text = grouper.group(crateId, List.of("How to deploy", "how to deploy.", "Other"));
    assertThat(text.method()).isEqualTo("text");
    assertThat(text.clusters()).containsExactly(List.of("How to deploy", "how to deploy."), List.of("Other"));

    judgeAvailable = false;
    when(embeddings.available()).thenReturn(false);
    assertThat(grouper.group(crateId, topics).method()).isEqualTo("text");
  }

  @Test
  void topicsBeyondTheCapAreOnlyMergedByExactText() throws Exception {
    when(embeddings.available()).thenReturn(true);
    when(embeddings.embedQueries(any())).thenAnswer(i -> ((List<?>) i.getArgument(0)).stream().map(x -> new float[] {1, 0}).toList());
    List<String> many = new ArrayList<>();
    for (int i = 0; i < TopicGrouper.MAX_TOPICS + 2; i++) many.add("t" + i);
    var result = grouper.group(crateId, many);
    assertThat(result.clusters()).hasSize(3); // one cluster for the capped head, the two others alone
  }
}
