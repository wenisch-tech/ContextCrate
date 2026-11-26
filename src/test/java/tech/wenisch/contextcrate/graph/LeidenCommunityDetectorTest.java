package tech.wenisch.contextcrate.graph;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.*;
import org.junit.jupiter.api.Test;

class LeidenCommunityDetectorTest {
  private final LeidenCommunityDetector detector = new LeidenCommunityDetector();

  /** Edges of cliques of the given sizes (node ids consecutive), each clique joined to the next by one weak bridge. */
  private static int[][] cliques(int... sizes) {
    List<int[]> edges = new ArrayList<>();
    int start = 0;
    for (int s = 0; s < sizes.length; s++) {
      for (int a = start; a < start + sizes[s]; a++) for (int b = a + 1; b < start + sizes[s]; b++) edges.add(new int[] {a, b});
      if (s + 1 < sizes.length) edges.add(new int[] {start + sizes[s] - 1, start + sizes[s]});
      start += sizes[s];
    }
    int[] src = edges.stream().mapToInt(e -> e[0]).toArray(), dst = edges.stream().mapToInt(e -> e[1]).toArray();
    return new int[][] {src, dst, {start}};
  }

  private static double[] ones(int n) { double[] w = new double[n]; Arrays.fill(w, 1); return w; }

  @Test
  void twoCliquesJoinedByABridgeAreTwoCommunities() {
    var g = cliques(5, 5);
    int[] c = detector.partition(g[2][0], g[0], g[1], ones(g[0].length));
    assertThat(Arrays.stream(c).distinct().count()).isEqualTo(2);
    for (int i = 1; i < 5; i++) assertThat(c[i]).isEqualTo(c[0]);
    for (int i = 6; i < 10; i++) assertThat(c[i]).isEqualTo(c[5]);
    assertThat(c[0]).isNotEqualTo(c[5]);
  }

  @Test
  void isDeterministic() {
    var g = cliques(6, 4, 7, 5);
    int[] a = detector.partition(g[2][0], g[0], g[1], ones(g[0].length));
    int[] b = new LeidenCommunityDetector().partition(g[2][0], g[0], g[1], ones(g[0].length));
    assertThat(a).containsExactly(b);
    assertThat(Arrays.stream(a).distinct().count()).isEqualTo(4);
  }

  @Test
  void heavyEdgesPullNodesTogether() {
    // A square: the strong pairs (0,1) and (2,3) form the communities.
    int[] src = {0, 1, 2, 3}, dst = {1, 2, 3, 0};
    double[] w = {10, 1, 10, 1};
    int[] c = detector.partition(4, src, dst, w);
    assertThat(c[0]).isEqualTo(c[1]);
    assertThat(c[2]).isEqualTo(c[3]);
    assertThat(c[0]).isNotEqualTo(c[2]);
  }

  @Test
  void hierarchySplitsCommunitiesLargerThanTheMaximum() {
    // Ring of 40 triangles: modularity's resolution limit merges neighbouring triangles on the whole graph,
    // and the merged groups are split again on the next level.
    List<int[]> edges = new ArrayList<>();
    int rings = 40;
    for (int r = 0; r < rings; r++) {
      int s = r * 3;
      edges.add(new int[] {s, s + 1}); edges.add(new int[] {s + 1, s + 2}); edges.add(new int[] {s, s + 2});
      edges.add(new int[] {s + 2, ((r + 1) % rings) * 3});
    }
    int[] src = edges.stream().mapToInt(e -> e[0]).toArray(), dst = edges.stream().mapToInt(e -> e[1]).toArray();
    var clusters = detector.hierarchy(rings * 3, src, dst, ones(src.length), 3, 3);
    assertThat(clusters).anyMatch(c -> c.level() == 0 && c.members().length > 3);
    assertThat(clusters).anyMatch(c -> c.level() == 1);
    for (var c : clusters) {
      if (c.level() == 0) { assertThat(c.parent()).isEqualTo(-1); continue; }
      var parent = clusters.get(c.parent());
      assertThat(parent.level()).isEqualTo(c.level() - 1);
      assertThat(parent.members().length).isGreaterThan(3);
      assertThat(Arrays.stream(parent.members()).boxed().toList()).containsAll(Arrays.stream(c.members()).boxed().toList());
    }
  }

  @Test
  void emptyAndEdgelessGraphs() {
    assertThat(detector.partition(0, new int[0], new int[0], new double[0])).isEmpty();
    assertThat(detector.partition(3, new int[0], new int[0], new double[0])).containsExactly(0, 1, 2);
    assertThat(detector.hierarchy(3, new int[0], new int[0], new double[0], 25, 4)).isEmpty();
  }
}
