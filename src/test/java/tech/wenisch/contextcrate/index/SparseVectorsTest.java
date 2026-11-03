package tech.wenisch.contextcrate.index;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class SparseVectorsTest {
  @Test
  void identifiersAreSplitSoPartsAndTheWholeMatch() {
    assertThat(SparseVectors.terms("getUserName")).contains("getusername", "get", "user", "name");
    assertThat(SparseVectors.terms("snake_case_name")).contains("snake", "case", "name");
    var document = SparseVectors.query("user_name");
    var query = SparseVectors.query("getUserName");
    assertThat(document.indices()).contains(SparseVectors.hash("name"));
    assertThat(query.indices()).contains(SparseVectors.hash("name"), SparseVectors.hash("user"));
  }

  @Test
  void hashIsStableAndUnsigned() {
    assertThat(SparseVectors.hash("rotation")).isEqualTo(SparseVectors.hash("rotation")).isBetween(0L, 0xffffffffL);
    assertThat(SparseVectors.hash("a")).isEqualTo(0xe40c292cL); // FNV-1a 32 bit of "a"
    assertThat(SparseVectors.hash("rotation")).isNotEqualTo(SparseVectors.hash("rotations"));
  }

  @Test
  void termFrequencySaturatesAndFieldsAreBoosted() {
    var once = SparseVectors.document(List.of(new SparseVectors.Field("pager", 1)), 100);
    var many = SparseVectors.document(List.of(new SparseVectors.Field("pager pager pager pager pager pager pager pager", 1)), 100);
    assertThat(once.values().getFirst()).isEqualTo(1f);                       // tf 1: 1 * 2.2 / 2.2
    assertThat(many.values().getFirst()).isGreaterThan(once.values().getFirst()).isLessThan(2.2f); // saturates below k1 + 1
    var boosted = SparseVectors.document(List.of(new SparseVectors.Field("pager", 3), new SparseVectors.Field("pager", 1)), 100);
    assertThat(boosted.values().getFirst()).isGreaterThan(many.values().getFirst() * 0.5f);
  }

  @Test
  void queryHasWeightOnePerDistinctTermInAscendingIndexOrder() {
    var query = SparseVectors.query("Monday monday MONDAY pager");
    assertThat(query.indices()).hasSize(2).isSorted().doesNotHaveDuplicates();
    assertThat(query.values()).containsOnly(1f);
    assertThat(SparseVectors.query("  ").isEmpty()).isTrue();
  }

  @Test
  void anOversizedDocumentKeepsItsHighestWeightedTerms() {
    StringBuilder text = new StringBuilder();
    for (int i = 0; i < 50; i++) text.append("term").append(i).append(' ');
    var capped = SparseVectors.document(List.of(new SparseVectors.Field("important important important", 1), new SparseVectors.Field(text.toString(), 1)), 10);
    assertThat(capped.indices()).hasSize(10).isSorted().contains(SparseVectors.hash("important"));
  }
}
