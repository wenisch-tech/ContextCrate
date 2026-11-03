package tech.wenisch.contextcrate.service.chunking;

import java.util.Arrays;

/** How the cut-off distance is derived from all distances between neighbouring sentences of a document. */
public enum BreakpointMethod {
  /** The given percentile (e.g. 95: the 5 % largest distances become breaks). */
  PERCENTILE {
    @Override public double threshold(double[] distances, double value) {
      return percentile(distances, value / 100.0);
    }
  },
  /** Mean plus {@code value} standard deviations. */
  STDDEV {
    @Override public double threshold(double[] distances, double value) {
      double mean = Arrays.stream(distances).average().orElse(0);
      double variance = Arrays.stream(distances).map(d -> (d - mean) * (d - mean)).average().orElse(0);
      return mean + value * Math.sqrt(variance);
    }
  },
  /** Third quartile plus {@code value} times the interquartile range. */
  IQR {
    @Override public double threshold(double[] distances, double value) {
      double q1 = percentile(distances, 0.25), q3 = percentile(distances, 0.75);
      return q3 + value * (q3 - q1);
    }
  };

  public abstract double threshold(double[] distances, double value);

  public static BreakpointMethod of(String name) {
    if ("stddev".equalsIgnoreCase(name)) return STDDEV;
    if ("iqr".equalsIgnoreCase(name)) return IQR;
    return PERCENTILE;
  }

  /** Linear interpolation between the closest ranks (the usual "type 7" definition). */
  static double percentile(double[] values, double fraction) {
    if (values.length == 0) return 0;
    double[] sorted = values.clone();
    Arrays.sort(sorted);
    double rank = Math.max(0, Math.min(1, fraction)) * (sorted.length - 1);
    int low = (int) Math.floor(rank), high = (int) Math.ceil(rank);
    return sorted[low] + (sorted[high] - sorted[low]) * (rank - low);
  }
}
