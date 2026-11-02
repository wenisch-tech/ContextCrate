package tech.wenisch.contextcrate.evaluation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Ranking metrics for retrieval quality. All methods take the relevance grades of the retrieved
 * chunks <em>in rank order</em> (index 0 = rank 1). A grade is an integer from 0 (irrelevant)
 * upwards; a chunk counts as relevant when its grade is at least {@link #RELEVANT}.
 *
 * <p>Methods that need to know how many relevant chunks exist in total return {@code null} when
 * that number is zero, because the value is then undefined (nothing could have been found); callers
 * must not record it.
 */
public final class RankingMetrics {
  /** Grades are 0-3; 2 ("relevant") and 3 ("highly relevant") count as relevant. */
  public static final int RELEVANT = 2;
  public static final int MAX_GRADE = 3;

  private RankingMetrics() {}

  public static boolean relevant(int grade) { return grade >= RELEVANT; }

  /** Relevant chunks in the top K divided by K (K is capped at the number of chunks, a shorter list is not penalised twice). */
  public static Double precisionAtK(List<Integer> grades, int k) {
    int n = Math.min(Math.max(k, 0), grades.size());
    if (n == 0) return null;
    return relevantIn(grades, n) / (double) n;
  }

  /** Relevant chunks in the top K divided by all relevant chunks that exist; {@code null} when none exist. */
  public static Double recallAtK(List<Integer> grades, int k, int totalRelevant) {
    if (totalRelevant <= 0) return null;
    int n = Math.min(Math.max(k, 0), grades.size());
    return Math.min(1.0, relevantIn(grades, n) / (double) totalRelevant);
  }

  /** 1 / rank of the first relevant chunk within the top K; 0 when there is none. The mean over questions is MRR. */
  public static double reciprocalRank(List<Integer> grades, int k) {
    int n = Math.min(Math.max(k, 0), grades.size());
    for (int i = 0; i < n; i++) if (relevant(grades.get(i))) return 1.0 / (i + 1);
    return 0.0;
  }

  /**
   * Average precision: the mean of precision@i over every rank i in the top K that holds a relevant
   * chunk, divided by the total number of relevant chunks. The mean over questions is MAP.
   */
  public static Double averagePrecision(List<Integer> grades, int k, int totalRelevant) {
    if (totalRelevant <= 0) return null;
    int n = Math.min(Math.max(k, 0), grades.size());
    double sum = 0;
    int found = 0;
    for (int i = 0; i < n; i++) {
      if (relevant(grades.get(i))) { found++; sum += found / (double) (i + 1); }
    }
    return Math.min(1.0, sum / totalRelevant);
  }

  /**
   * Normalized discounted cumulative gain at K with gain 2^grade - 1 and a log2 discount. The ideal
   * ordering is taken from {@code allGrades}, the grades of every known chunk (the judged pool or
   * the golden set); {@code null} when the ideal DCG is zero (nothing relevant is known).
   */
  public static Double ndcg(List<Integer> grades, int k, List<Integer> allGrades) {
    int n = Math.min(Math.max(k, 0), grades.size());
    double ideal = dcg(allGrades.stream().sorted(Comparator.reverseOrder()).toList(), k);
    if (ideal <= 0) return null;
    return Math.min(1.0, dcg(grades, n) / ideal);
  }

  static double dcg(List<Integer> grades, int k) {
    double sum = 0;
    int n = Math.min(Math.max(k, 0), grades.size());
    for (int i = 0; i < n; i++) sum += (Math.pow(2, Math.max(0, grades.get(i))) - 1) / (Math.log(i + 2) / Math.log(2));
    return sum;
  }

  public static int relevantCount(List<Integer> grades) { return relevantIn(grades, grades.size()); }

  private static int relevantIn(List<Integer> grades, int n) {
    int count = 0;
    for (int i = 0; i < n; i++) if (relevant(grades.get(i))) count++;
    return count;
  }

  /** Cosine similarity of two vectors, 0 when either has no length. */
  public static double cosine(float[] a, float[] b) {
    int n = Math.min(a.length, b.length);
    double dot = 0, na = 0, nb = 0;
    for (int i = 0; i < n; i++) { dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i]; }
    return na == 0 || nb == 0 ? 0 : dot / (Math.sqrt(na) * Math.sqrt(nb));
  }

  /** Mean of the non-null values, or {@code null} when there are none. */
  public static Double mean(List<Double> values) {
    List<Double> present = new ArrayList<>();
    for (Double v : values) if (v != null) present.add(v);
    if (present.isEmpty()) return null;
    return present.stream().mapToDouble(Double::doubleValue).average().orElse(0);
  }
}
