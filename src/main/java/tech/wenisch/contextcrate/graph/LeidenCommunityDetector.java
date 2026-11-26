package tech.wenisch.contextcrate.graph;

import java.util.*;

/**
 * Community detection with the Leiden algorithm (Traag, Waltman &amp; van Eck 2019) on an undirected weighted graph,
 * maximising modularity: (1) fast local moving of nodes between communities, (2) refinement, in which every community is
 * rebuilt from singletons that only merge with well-connected parts of it (so no community is internally disconnected),
 * (3) aggregation of the refined partition, starting the next round from the unrefined one. Rounds repeat until nothing
 * moves. Node orders come from a seeded random generator, so the result is deterministic.
 *
 * <p>{@link #hierarchy} applies it recursively: communities larger than a maximum size are partitioned again, which gives
 * levels from coarse (0) to fine.
 */
public final class LeidenCommunityDetector {
  private static final double EPS = 1e-12;
  private final double resolution;
  private final long seed;

  public LeidenCommunityDetector() { this(1.0, 42L); }

  public LeidenCommunityDetector(double resolution, long seed) { this.resolution = resolution; this.seed = seed; }

  /** A community of the hierarchy; {@code parent} is the index of the parent cluster in the list, or -1 on level 0. */
  public record Cluster(int level, int parent, int[] members) {}

  /**
   * Hierarchical communities. Level 0 partitions the whole graph; every cluster larger than {@code maxSize} is partitioned
   * again on the next level, up to {@code maxLevels} levels. Isolated nodes and single-node clusters are left out.
   */
  public List<Cluster> hierarchy(int n, int[] source, int[] target, double[] weight, int maxSize, int maxLevels) {
    List<Cluster> out = new ArrayList<>();
    int[] all = new int[n];
    for (int i = 0; i < n; i++) all[i] = i;
    split(all, source, target, weight, 0, -1, maxSize, maxLevels, out, true);
    return out;
  }

  private void split(int[] nodes, int[] source, int[] target, double[] weight, int level, int parent, int maxSize, int maxLevels, List<Cluster> out, boolean force) {
    // Induced subgraph of `nodes`.
    Map<Integer, Integer> local = new HashMap<>();
    for (int i = 0; i < nodes.length; i++) local.put(nodes[i], i);
    List<int[]> edges = new ArrayList<>(); List<Double> weights = new ArrayList<>();
    for (int e = 0; e < source.length; e++) {
      Integer a = local.get(source[e]), b = local.get(target[e]);
      if (a != null && b != null && !a.equals(b) && weight[e] > 0) { edges.add(new int[] {a, b}); weights.add(weight[e]); }
    }
    int[] s = new int[edges.size()], t = new int[edges.size()]; double[] w = new double[edges.size()];
    for (int e = 0; e < s.length; e++) { s[e] = edges.get(e)[0]; t[e] = edges.get(e)[1]; w[e] = weights.get(e); }
    int[] membership = partition(nodes.length, s, t, w);
    Map<Integer, List<Integer>> groups = new TreeMap<>();
    for (int i = 0; i < nodes.length; i++) groups.computeIfAbsent(membership[i], k -> new ArrayList<>()).add(nodes[i]);
    if (!force && groups.size() < 2) return; // could not be split further
    List<int[]> clusters = groups.values().stream().filter(g -> g.size() > 1).map(g -> g.stream().mapToInt(Integer::intValue).toArray()).toList();
    for (int[] members : clusters) {
      out.add(new Cluster(level, parent, members));
      int index = out.size() - 1;
      if (members.length > maxSize && level + 1 < maxLevels) split(members, source, target, weight, level + 1, index, maxSize, maxLevels, out, false);
    }
  }

  /** Community of every node (ids 0..k-1). Nodes without edges stay alone. */
  public int[] partition(int n, int[] source, int[] target, double[] weight) {
    if (n == 0) return new int[0];
    Random random = new Random(seed);
    Net net = Net.of(n, source, target, weight);
    int[] membership = new int[n]; // original node -> node of the current aggregate network
    for (int i = 0; i < n; i++) membership[i] = i;
    int[] partition = identity(n);
    if (net.twoM <= 0) return partition;
    for (int round = 0; round < 50; round++) {
      boolean moved = moveNodes(net, partition, random);
      int communities = renumber(partition);
      if (!moved && communities == net.n) break; // every community is one aggregate node: done
      int[] refined = refine(net, partition, random);
      int refinedCount = renumber(refined);
      // Aggregate on the refined partition; its nodes start in the community their refined part belongs to.
      int[] next = new int[refinedCount];
      for (int v = 0; v < net.n; v++) next[refined[v]] = partition[v];
      for (int i = 0; i < n; i++) membership[i] = refined[membership[i]];
      if (refinedCount == net.n) { partition = next; if (!moved) break; continue; }
      net = net.aggregate(refined, refinedCount);
      partition = next;
    }
    int[] result = new int[n];
    for (int i = 0; i < n; i++) result[i] = partition[membership[i]];
    renumber(result);
    return result;
  }

  /** Fast local moving: nodes go to the neighbouring community with the largest modularity gain until none improves. */
  private boolean moveNodes(Net net, int[] community, Random random) {
    int n = net.n;
    double[] total = new double[n];
    for (int v = 0; v < n; v++) total[community[v]] += net.k[v];
    double[] toCommunity = new double[n];
    int[] touched = new int[n];
    Deque<Integer> queue = new ArrayDeque<>();
    boolean[] queued = new boolean[n];
    for (int v : shuffled(n, random)) { queue.add(v); queued[v] = true; }
    boolean movedAny = false;
    while (!queue.isEmpty()) {
      int v = queue.poll(); queued[v] = false;
      int own = community[v], count = 0;
      for (int e = 0; e < net.nbr[v].length; e++) {
        int c = community[net.nbr[v][e]];
        if (toCommunity[c] == 0) touched[count++] = c;
        toCommunity[c] += net.w[v][e];
      }
      total[own] -= net.k[v];
      int best = own;
      double bestGain = toCommunity[own] - resolution * net.k[v] * total[own] / net.twoM;
      for (int i = 0; i < count; i++) {
        int c = touched[i];
        double gain = toCommunity[c] - resolution * net.k[v] * total[c] / net.twoM;
        if (gain > bestGain + EPS) { bestGain = gain; best = c; }
      }
      total[best] += net.k[v];
      for (int i = 0; i < count; i++) toCommunity[touched[i]] = 0;
      if (best != own) {
        community[v] = best; movedAny = true;
        for (int u : net.nbr[v]) if (community[u] != best && !queued[u]) { queue.add(u); queued[u] = true; }
      }
    }
    return movedAny;
  }

  /**
   * Refinement: inside every community, nodes start alone; a node that is still alone and well connected to its community
   * merges into the well-connected refined part with the largest gain (never a negative one).
   */
  private int[] refine(Net net, int[] community, Random random) {
    int n = net.n;
    int[] refined = identity(n);
    double[] total = new double[n], communityTotal = new double[n], toOwn = new double[n], partToRest = new double[n];
    int[] size = new int[n];
    for (int v = 0; v < n; v++) { total[v] = net.k[v]; size[v] = 1; communityTotal[community[v]] += net.k[v]; }
    for (int v = 0; v < n; v++) for (int e = 0; e < net.nbr[v].length; e++) if (community[net.nbr[v][e]] == community[v]) toOwn[v] += net.w[v][e];
    for (int v = 0; v < n; v++) partToRest[v] = toOwn[v];
    double[] toPart = new double[n];
    int[] touched = new int[n];
    for (int v : shuffled(n, random)) {
      if (size[refined[v]] != 1) continue; // only singletons move
      double rest = communityTotal[community[v]] - net.k[v];
      if (toOwn[v] < resolution * net.k[v] * rest / net.twoM - EPS) continue; // not well connected to its community
      int count = 0;
      for (int e = 0; e < net.nbr[v].length; e++) {
        int u = net.nbr[v][e];
        if (community[u] != community[v]) continue;
        int r = refined[u];
        if (toPart[r] == 0) touched[count++] = r;
        toPart[r] += net.w[v][e];
      }
      int best = refined[v]; double bestGain = 0;
      for (int i = 0; i < count; i++) {
        int r = touched[i];
        double ct = communityTotal[community[v]];
        boolean wellConnected = partToRest[r] >= resolution * total[r] * (ct - total[r]) / net.twoM - EPS;
        double gain = toPart[r] - resolution * net.k[v] * total[r] / net.twoM;
        if (wellConnected && gain > bestGain + EPS) { bestGain = gain; best = r; }
      }
      if (best != refined[v]) {
        int own = refined[v];
        partToRest[best] = partToRest[best] + toOwn[v] - 2 * toPart[best];
        total[best] += net.k[v]; size[best]++;
        total[own] = 0; size[own] = 0;
        refined[v] = best;
      }
      for (int i = 0; i < count; i++) toPart[touched[i]] = 0;
    }
    return refined;
  }

  private static int[] identity(int n) { int[] a = new int[n]; for (int i = 0; i < n; i++) a[i] = i; return a; }

  private static int[] shuffled(int n, Random random) {
    int[] order = identity(n);
    for (int i = n - 1; i > 0; i--) { int j = random.nextInt(i + 1); int tmp = order[i]; order[i] = order[j]; order[j] = tmp; }
    return order;
  }

  /** Renumbers labels to 0..k-1 in order of first appearance and returns k. */
  private static int renumber(int[] labels) {
    Map<Integer, Integer> ids = new HashMap<>();
    for (int i = 0; i < labels.length; i++) labels[i] = ids.computeIfAbsent(labels[i], k -> ids.size());
    return ids.size();
  }

  /** Adjacency lists without self-loops; {@code k} includes self-loop weight, so the total weight is kept by aggregation. */
  private static final class Net {
    int n; int[][] nbr; double[][] w; double[] k; double twoM;

    static Net of(int n, int[] source, int[] target, double[] weight) {
      List<Map<Integer, Double>> adj = new ArrayList<>(n);
      for (int i = 0; i < n; i++) adj.add(new HashMap<>());
      double[] k = new double[n];
      for (int e = 0; e < source.length; e++) {
        int a = source[e], b = target[e]; double w = weight[e];
        if (w <= 0) continue;
        k[a] += w; k[b] += w;
        if (a != b) { adj.get(a).merge(b, w, Double::sum); adj.get(b).merge(a, w, Double::sum); }
      }
      return build(n, adj, k);
    }

    private static Net build(int n, List<Map<Integer, Double>> adj, double[] k) {
      Net net = new Net(); net.n = n; net.k = k; net.nbr = new int[n][]; net.w = new double[n][];
      for (int i = 0; i < n; i++) {
        var entries = new ArrayList<>(adj.get(i).entrySet());
        entries.sort(Map.Entry.comparingByKey());
        net.nbr[i] = new int[entries.size()]; net.w[i] = new double[entries.size()];
        for (int j = 0; j < entries.size(); j++) { net.nbr[i][j] = entries.get(j).getKey(); net.w[i][j] = entries.get(j).getValue(); }
      }
      for (double v : k) net.twoM += v;
      return net;
    }

    Net aggregate(int[] group, int groups) {
      List<Map<Integer, Double>> adj = new ArrayList<>(groups);
      for (int i = 0; i < groups; i++) adj.add(new HashMap<>());
      double[] k2 = new double[groups];
      for (int v = 0; v < n; v++) {
        k2[group[v]] += k[v];
        for (int e = 0; e < nbr[v].length; e++) {
          int a = group[v], b = group[nbr[v][e]];
          if (a != b) adj.get(a).merge(b, w[v][e], Double::sum);
        }
      }
      return build(groups, adj, k2);
    }
  }
}
