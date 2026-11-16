package tech.wenisch.contextcrate.answer;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import tech.wenisch.contextcrate.config.CrateContext;
import tech.wenisch.contextcrate.domain.RagSettings;
import tech.wenisch.contextcrate.embedding.EmbeddingProvider;
import tech.wenisch.contextcrate.index.SearchIndex.SearchHit;
import tech.wenisch.contextcrate.index.SearchIndex.SearchResults;
import tech.wenisch.contextcrate.metrics.CrateMetrics;

/**
 * Dartboard retrieval: from N = K x oversampling candidates the K chunks are chosen that are relevant for the
 * question <em>and</em> different from each other. Near-duplicates of an already chosen chunk are pushed down.
 *
 * <p>Distances are cosine distances (1 - cosine similarity). The first chunk is the one closest to the question.
 * Every further slot goes to the candidate with the highest
 * {@code relevanceWeight * relevance + diversityWeight * diversity}, where, in log space with smoothing
 * parameter sigma,
 * <ul>
 *   <li>relevance = {@code -d(query, c)^2 / (2 sigma^2)} (Gaussian log-density of the distance to the question), and</li>
 *   <li>diversity = {@code -logsumexp_s(-d(c, s)^2 / (2 sigma^2))} over the chosen chunks s, a smooth minimum
 *       of the distance to the chunks already chosen.</li>
 * </ul>
 *
 * <p>The vectors of the candidates are computed again from the texts that were indexed, so it works with every
 * search backend. Fail-open: if anything is missing or fails, the candidates keep their order and are cut to K.
 */
@Service
public class DartboardService {
  private static final Logger log = LoggerFactory.getLogger(DartboardService.class);
  private final EmbeddingProvider embeddings;
  private CrateMetrics metrics = CrateMetrics.noop();

  public DartboardService(EmbeddingProvider embeddings) { this.embeddings = embeddings; }

  @Autowired(required = false)
  public void setMetrics(CrateMetrics metrics) { this.metrics = metrics; }

  /** Number of candidates to retrieve for {@code k} results. */
  public static int candidateLimit(int k, int oversampling) { return Math.max(k, Math.min(100, k * Math.max(1, oversampling))); }

  /**
   * @return the candidates reordered by the dartboard selection and cut to {@code k}; the unchanged leading
   *     {@code k} candidates when selection is not possible
   */
  public SearchResults apply(UUID crateId, String question, SearchResults candidates, int k, RagSettings policy) {
    List<SearchHit> hits = candidates.hits();
    if (hits.size() <= 1) return candidates;
    long started = System.nanoTime();
    String outcome = "applied";
    try (var ignored = CrateContext.use(crateId)) {
      if (!embeddings.available()) { outcome = "skipped"; return cut(candidates, k); }
      float[] query = embeddings.embedQuery(question);
      List<String> texts = hits.stream().map(h -> h.retrievalContent() != null ? h.retrievalContent() : h.snippet() == null ? "" : h.snippet()).toList();
      List<float[]> vectors = embeddings.embedDocuments(texts);
      if (vectors == null || vectors.size() != hits.size()) throw new IllegalStateException("Embedding count does not match the candidates");
      List<Integer> chosen = select(query, vectors, k, policy.getDartboardRelevanceWeight(), policy.getDartboardDiversityWeight(), policy.getDartboardSigma());
      List<SearchHit> selected = new ArrayList<>();
      for (int index : chosen) selected.add(hits.get(index));
      metrics.summary("answer.dartboard.candidates", crateId, hits.size());
      return new SearchResults(candidates.query(), candidates.mode(), selected);
    } catch (Exception e) {
      outcome = "error";
      log.warn("Dartboard selection failed; using the retrieval order. crateId={}: {}", crateId, e.getMessage());
      return cut(candidates, k);
    } finally {
      metrics.count("answer.dartboard", crateId, "outcome", outcome);
      metrics.time("answer.dartboard.duration", crateId, System.nanoTime() - started);
    }
  }

  private static SearchResults cut(SearchResults results, int k) {
    return results.hits().size() <= k ? results : new SearchResults(results.query(), results.mode(), results.hits().subList(0, k));
  }

  /**
   * The dartboard selection itself.
   *
   * @return indexes into {@code candidates}, in the order they were chosen; at most {@code k}
   * @throws IllegalArgumentException when vectors have different lengths
   */
  public static List<Integer> select(float[] query, List<float[]> candidates, int k, double relevanceWeight, double diversityWeight, double sigma) {
    int n = candidates.size();
    int want = Math.max(0, Math.min(k, n));
    List<Integer> chosen = new ArrayList<>(want);
    if (want == 0) return chosen;
    double scale = 2 * sigma * sigma;
    double[] toQuery = new double[n];
    for (int i = 0; i < n; i++) toQuery[i] = distance(query, candidates.get(i));
    double[][] between = new double[n][n];
    for (int i = 0; i < n; i++)
      for (int j = i + 1; j < n; j++) between[i][j] = between[j][i] = distance(candidates.get(i), candidates.get(j));

    boolean[] taken = new boolean[n];
    int first = 0;
    for (int i = 1; i < n; i++) if (toQuery[i] < toQuery[first]) first = i;
    chosen.add(first);
    taken[first] = true;
    while (chosen.size() < want) {
      int best = -1;
      double bestScore = Double.NEGATIVE_INFINITY;
      for (int i = 0; i < n; i++) {
        if (taken[i]) continue;
        double relevance = -(toQuery[i] * toQuery[i]) / scale;
        double[] terms = new double[chosen.size()];
        for (int s = 0; s < terms.length; s++) { double d = between[i][chosen.get(s)]; terms[s] = -(d * d) / scale; }
        double diversity = -logSumExp(terms);
        double score = relevanceWeight * relevance + diversityWeight * diversity;
        // ties: closer to the question first, then input order
        if (best < 0 || score > bestScore || (score == bestScore && toQuery[i] < toQuery[best])) { best = i; bestScore = score; }
      }
      chosen.add(best);
      taken[best] = true;
    }
    return chosen;
  }

  /** Cosine distance, 1 - cos; 1 for a zero vector. */
  static double distance(float[] a, float[] b) {
    if (a.length != b.length) throw new IllegalArgumentException("Vectors have different lengths");
    double dot = 0, na = 0, nb = 0;
    for (int i = 0; i < a.length; i++) { dot += (double) a[i] * b[i]; na += (double) a[i] * a[i]; nb += (double) b[i] * b[i]; }
    if (na == 0 || nb == 0) return 1;
    double cos = dot / Math.sqrt(na * nb);
    return 1 - Math.max(-1, Math.min(1, cos));
  }

  private static double logSumExp(double[] values) {
    double max = Double.NEGATIVE_INFINITY;
    for (double v : values) max = Math.max(max, v);
    double sum = 0;
    for (double v : values) sum += Math.exp(v - max);
    return max + Math.log(sum);
  }
}
