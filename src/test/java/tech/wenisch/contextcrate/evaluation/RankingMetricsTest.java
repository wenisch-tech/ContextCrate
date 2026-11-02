package tech.wenisch.contextcrate.evaluation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class RankingMetricsTest {
  // Ranks 1..5: relevant, irrelevant, relevant, irrelevant, relevant (grades 3,0,2,1,2).
  private final List<Integer> grades = List.of(3, 0, 2, 1, 2);

  @Test
  void precisionAtKCountsRelevantChunksInTheTopK() {
    assertThat(RankingMetrics.precisionAtK(grades, 5)).isCloseTo(3 / 5.0, within(1e-9));
    assertThat(RankingMetrics.precisionAtK(grades, 3)).isCloseTo(2 / 3.0, within(1e-9));
    assertThat(RankingMetrics.precisionAtK(grades, 1)).isEqualTo(1.0);
    // K larger than the list: only the delivered chunks count.
    assertThat(RankingMetrics.precisionAtK(grades, 10)).isCloseTo(3 / 5.0, within(1e-9));
    assertThat(RankingMetrics.precisionAtK(List.of(), 5)).isNull();
  }

  @Test
  void recallAtKDividesByAllRelevantChunksThatExist() {
    assertThat(RankingMetrics.recallAtK(grades, 3, 4)).isCloseTo(2 / 4.0, within(1e-9));
    assertThat(RankingMetrics.recallAtK(grades, 5, 3)).isEqualTo(1.0);
    // Nothing relevant exists: undefined, not 0 or 1.
    assertThat(RankingMetrics.recallAtK(grades, 5, 0)).isNull();
  }

  @Test
  void reciprocalRankUsesTheFirstRelevantRank() {
    assertThat(RankingMetrics.reciprocalRank(grades, 5)).isEqualTo(1.0);
    assertThat(RankingMetrics.reciprocalRank(List.of(0, 1, 2), 3)).isCloseTo(1 / 3.0, within(1e-9));
    assertThat(RankingMetrics.reciprocalRank(List.of(0, 1, 0), 3)).isEqualTo(0.0);
    // A relevant chunk outside the top K does not count.
    assertThat(RankingMetrics.reciprocalRank(List.of(0, 0, 3), 2)).isEqualTo(0.0);
  }

  @Test
  void averagePrecisionAveragesPrecisionAtEachRelevantRank() {
    // precision at the relevant ranks: 1/1, 2/3, 3/5 -> (1 + 0.6667 + 0.6) / 3 relevant in total
    assertThat(RankingMetrics.averagePrecision(grades, 5, 3)).isCloseTo((1 + 2 / 3.0 + 3 / 5.0) / 3, within(1e-9));
    // Missing relevant chunks (5 exist, 3 found) lower the score.
    assertThat(RankingMetrics.averagePrecision(grades, 5, 5)).isCloseTo((1 + 2 / 3.0 + 3 / 5.0) / 5, within(1e-9));
    assertThat(RankingMetrics.averagePrecision(grades, 5, 0)).isNull();
  }

  @Test
  void ndcgComparesTheRankingWithTheIdealOrder() {
    // DCG = 7/1 + 0 + 3/2 + 1/2.3219 + 3/2.5849; ideal over {3,2,2,1,0}: 7 + 3/1.585 + 3/2 + 1/2.3219
    double dcg = 7 + 0 + 3 / 2.0 + 1 / (Math.log(5) / Math.log(2)) + 3 / (Math.log(6) / Math.log(2));
    double ideal = 7 + 3 / (Math.log(3) / Math.log(2)) + 3 / 2.0 + 1 / (Math.log(5) / Math.log(2)) + 0;
    assertThat(RankingMetrics.ndcg(grades, 5, List.of(3, 2, 2, 1, 0))).isCloseTo(dcg / ideal, within(1e-9));
    // The perfect order scores exactly 1.
    assertThat(RankingMetrics.ndcg(List.of(3, 2, 2, 1, 0), 5, List.of(3, 2, 2, 1, 0))).isCloseTo(1.0, within(1e-9));
    // Nothing relevant known: undefined.
    assertThat(RankingMetrics.ndcg(List.of(0, 0), 2, List.of(0, 0, 0))).isNull();
  }

  @Test
  void cosineAndMeanHelpers() {
    assertThat(RankingMetrics.cosine(new float[] {1, 0}, new float[] {1, 0})).isCloseTo(1.0, within(1e-9));
    assertThat(RankingMetrics.cosine(new float[] {1, 0}, new float[] {0, 1})).isCloseTo(0.0, within(1e-9));
    assertThat(RankingMetrics.cosine(new float[] {0, 0}, new float[] {1, 1})).isEqualTo(0.0);
    assertThat(RankingMetrics.mean(Arrays.asList(1.0, null, 0.0))).isCloseTo(0.5, within(1e-9));
    assertThat(RankingMetrics.mean(Arrays.asList((Double) null))).isNull();
  }
}
