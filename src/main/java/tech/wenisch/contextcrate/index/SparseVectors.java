package tech.wenisch.contextcrate.index;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;

/**
 * Keyword search for Qdrant. Qdrant has no analyzer or BM25 ranking, but a sparse vector with the
 * {@code idf} modifier gives the same behaviour: ContextCrate tokenizes the text with the analyzer the
 * Lucene backend uses (identifier splitting, case folding), sends the saturated term frequency of every
 * term, and Qdrant multiplies it with the collection's inverse document frequency at query time.
 *
 * <p>A term is identified by the 32-bit FNV-1a hash of its text, which is stable across JVMs and
 * restarts. Collisions are astronomically rare at normal vocabulary sizes and only add noise.
 */
final class SparseVectors {
  /** BM25 term-frequency saturation. */
  static final double K1 = 1.2;
  static final int DEFAULT_MAX_TERMS = 10_000;
  private static final CodeAwareAnalyzer ANALYZER = new CodeAwareAnalyzer();

  /** Parallel arrays in the shape Qdrant expects; {@code indices} are unsigned 32-bit values. */
  record Sparse(List<Long> indices, List<Float> values) {
    boolean isEmpty() { return indices.isEmpty(); }
    Map<String, Object> toQdrant() { return Map.of("indices", indices, "values", values); }
  }

  private SparseVectors() {}

  /** A field of a document with its weight: a term in the title counts three times, in the heading twice. */
  record Field(String text, int boost) {}

  static Sparse document(List<Field> fields, int maxTerms) {
    Map<Long, Double> frequency = new TreeMap<>();
    for (Field field : fields) {
      if (field.text() == null || field.text().isBlank()) continue;
      for (String term : terms(field.text())) frequency.merge(hash(term), (double) field.boost(), Double::sum);
    }
    List<Map.Entry<Long, Double>> entries = new ArrayList<>(frequency.entrySet());
    if (entries.size() > maxTerms) {
      entries.sort(Map.Entry.<Long, Double>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()));
      entries = new ArrayList<>(entries.subList(0, maxTerms));
      entries.sort(Map.Entry.comparingByKey());
    }
    List<Long> indices = new ArrayList<>(entries.size());
    List<Float> values = new ArrayList<>(entries.size());
    for (var entry : entries) {
      double tf = entry.getValue();
      indices.add(entry.getKey());
      values.add((float) (tf * (K1 + 1) / (tf + K1)));
    }
    return new Sparse(indices, values);
  }

  /** The query: every distinct term once with weight 1. */
  static Sparse query(String text) {
    Map<Long, Double> distinct = new TreeMap<>();
    for (String term : terms(text)) distinct.put(hash(term), 1.0);
    return new Sparse(new ArrayList<>(distinct.keySet()), distinct.values().stream().map(Double::floatValue).toList());
  }

  static List<String> terms(String text) {
    List<String> out = new ArrayList<>();
    try (TokenStream stream = ANALYZER.tokenStream("text", text)) {
      CharTermAttribute term = stream.addAttribute(CharTermAttribute.class);
      stream.reset();
      while (stream.incrementToken()) out.add(term.toString());
      stream.end();
    } catch (IOException e) {
      throw new IllegalStateException("Could not tokenize text", e);
    }
    return out;
  }

  /** FNV-1a, 32 bit, as an unsigned value. */
  static long hash(String term) {
    int h = 0x811c9dc5;
    for (byte b : term.getBytes(StandardCharsets.UTF_8)) {
      h ^= (b & 0xff);
      h *= 0x01000193;
    }
    return h & 0xffffffffL;
  }
}
