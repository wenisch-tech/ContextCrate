package tech.wenisch.contextcrate.answer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tech.wenisch.contextcrate.answer.AdaptiveRetrievalService.Candidate;
import tech.wenisch.contextcrate.answer.AdaptiveRetrievalService.Category;

class AdaptiveRetrievalServiceTest {
  private final UUID crate = UUID.randomUUID();
  private final AnswerGenerationProvider provider = mock(AnswerGenerationProvider.class);
  private final AdaptiveRetrievalService service = new AdaptiveRetrievalService(provider);

  private static List<Candidate> pool(int n) { return IntStream.range(0, n).mapToObj(i -> new Candidate("T" + i, "text " + i)).toList(); }

  @Test
  void classifiesEachCategory() throws Exception {
    when(provider.complete(any())).thenReturn("{\"category\":\"factual\"}", "Sure: {\"category\": \"Analytical\"}", "{\"category\":\"meinung\"}");
    assertThat(service.classify(crate, "q", List.of())).isEqualTo(Category.FACTUAL);
    assertThat(service.classify(crate, "q", List.of())).isEqualTo(Category.ANALYTICAL);
    assertThat(service.classify(crate, "q", List.of())).isEqualTo(Category.OPINION);
  }

  @Test
  void unusableClassificationAndModelFailureReturnNull() throws Exception {
    when(provider.complete(any())).thenReturn("no idea", "{\"category\":\"other\"}").thenThrow(new RuntimeException("down"));
    assertThat(service.classify(crate, "q", List.of())).isNull();
    assertThat(service.classify(crate, "q", List.of())).isNull();
    assertThat(service.classify(crate, "q", List.of())).isNull();
  }

  @Test
  void decomposeKeepsTheQuestionFirstAndCapsSubQuestions() throws Exception {
    when(provider.complete(any())).thenReturn("1. A?\n2) B?\n- C?\n* D?\nE?");
    assertThat(service.decompose(crate, "Original?", List.of())).containsExactly("Original?", "A?", "B?", "C?", "D?");
  }

  @Test
  void perspectivesAreCombinedWithTheOriginalQuestion() throws Exception {
    when(provider.complete(any())).thenReturn("supporters\n\"critics\"");
    assertThat(service.perspectives(crate, "Is X good?", List.of())).containsExactly("Is X good?", "Is X good? supporters", "Is X good? critics");
  }

  @Test
  void planningFailureSearchesTheOriginalQuestionOnly() throws Exception {
    when(provider.complete(any())).thenThrow(new RuntimeException("down"));
    assertThat(service.decompose(crate, "Q", List.of())).containsExactly("Q");
    assertThat(service.perspectives(crate, "Q", List.of())).containsExactly("Q");
  }

  @Test
  void selectReturnsTheChosenIndexesInModelOrder() throws Exception {
    when(provider.complete(any())).thenReturn("{\"selected\":[5,2,3]}");
    assertThat(service.select(crate, Category.ANALYTICAL, "Q", pool(6), 3)).containsExactly(4, 1, 2);
  }

  @Test
  void selectIgnoresInvalidAndDuplicateIdsAndFillsInPoolOrder() throws Exception {
    when(provider.complete(any())).thenReturn("{\"selected\":[4,4,99,0,\"x\"]}");
    assertThat(service.select(crate, Category.OPINION, "Q", pool(6), 3)).containsExactly(3, 0, 1);
  }

  @Test
  void selectKeepsTheOriginalRankingOnFailureOrGarbage() throws Exception {
    when(provider.complete(any())).thenReturn("garbage").thenThrow(new RuntimeException("down"));
    assertThat(service.select(crate, Category.FACTUAL, "Q", pool(6), 3)).containsExactly(0, 1, 2);
    assertThat(service.select(crate, Category.FACTUAL, "Q", pool(6), 3)).containsExactly(0, 1, 2);
  }

  @Test
  void selectSkipsTheModelWhenThePoolFitsAndCapsAtK() throws Exception {
    assertThat(service.select(crate, Category.FACTUAL, "Q", pool(2), 3)).containsExactly(0, 1);
    verify(provider, never()).complete(any());
  }

  @Test
  void promptsTreatInputAsUntrustedAndDifferPerCategory() throws Exception {
    when(provider.complete(any())).thenReturn("{\"selected\":[1]}");
    service.select(crate, Category.ANALYTICAL, "Q", pool(4), 1);
    service.select(crate, Category.OPINION, "Q", pool(4), 1);
    var captor = ArgumentCaptor.forClass(List.class);
    verify(provider, times(2)).complete(captor.capture());
    String analytical = captor.getAllValues().get(0).toString(), opinion = captor.getAllValues().get(1).toString();
    assertThat(analytical).contains("untrusted").contains("redundant");
    assertThat(opinion).contains("untrusted").contains("viewpoints");
  }
}
