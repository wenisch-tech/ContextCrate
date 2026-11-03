package tech.wenisch.contextcrate.service.chunking;

import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import tech.wenisch.contextcrate.domain.CrawlConfiguration.SemanticChunking;

/**
 * Cuts running prose where the meaning changes: sentences are embedded, the distance between every
 * two neighbouring sentences is measured, and the text is cut where the distance exceeds a threshold
 * derived from all distances of the document (percentile, standard deviation or interquartile range).
 *
 * <p>The document structure is kept: a heading is always a boundary, and code and images are chunked
 * by {@link BlockPacker} as before. Groups never exceed the chunk size and groups below the minimum are
 * merged into their closer neighbour. The sentence vectors are temporary; the finished chunks are
 * embedded and indexed by the normal indexing stage.
 */
public final class SemanticChunker {
  public static final int MAX_SENTENCES = 5_000, BATCH = 64;

  @FunctionalInterface
  public interface SentenceEmbedder {
    List<float[]> embed(List<String> sentences) throws Exception;
  }

  /** What happened, for metrics and the document details page. */
  public record Stats(int sentences, int breakpoints, double threshold, String fallback) {
    public static Stats fallback(String reason, int sentences) { return new Stats(sentences, 0, 0, reason); }
    public boolean applied() { return fallback == null; }
  }

  public record Result(List<ChunkDraft> drafts, Stats stats) {}

  private SemanticChunker() {}

  private record Sentence(String text, boolean paragraphStart) {}

  /** A run of prose under one heading: its sentences and where its distances start in the pooled array. */
  private static final class Run {
    final List<String> path;
    final List<Sentence> sentences = new ArrayList<>();
    double[] distances = new double[0]; // distances[i] = between sentence i and i + 1
    Run(List<String> path) { this.path = path; }
  }

  private sealed interface Segment permits Prose, Other {}
  private record Prose(Run run) implements Segment {}
  private record Other(List<Block> blocks) implements Segment {}

  public static Result pack(List<Block> blocks, ChunkOptions options, SemanticChunking config,
      SentenceEmbedder embedder) {
    List<Segment> segments = segments(blocks, options);
    int sentenceCount = segments.stream().mapToInt(s -> s instanceof Prose p ? p.run().sentences.size() : 0).sum();
    if (sentenceCount > MAX_SENTENCES) return standard(blocks, options, Stats.fallback("too_many_sentences", sentenceCount));
    if (sentenceCount < 2) return standard(blocks, options, Stats.fallback("too_short", sentenceCount));
    try {
      embedRuns(segments, embedder);
    } catch (Exception e) {
      return standard(blocks, options, Stats.fallback("error", sentenceCount));
    }
    int pooled = 0;
    for (Segment s : segments) if (s instanceof Prose p) pooled += p.run().distances.length;
    double[] all = new double[pooled];
    int at = 0;
    for (Segment s : segments)
      if (s instanceof Prose p) for (double d : p.run().distances) all[at++] = d;
    if (pooled < 3) return standard(blocks, options, Stats.fallback("too_short", sentenceCount));
    double threshold = BreakpointMethod.of(config.method()).threshold(all, config.value());

    List<ChunkDraft> drafts = new ArrayList<>();
    int breakpoints = 0;
    for (Segment segment : segments) {
      if (segment instanceof Other other) {
        drafts.addAll(BlockPacker.pack(other.blocks(), options));
      } else {
        Run run = ((Prose) segment).run();
        breakpoints += chunkRun(run, threshold, options, config.minChars(), drafts);
      }
    }
    return new Result(drafts, new Stats(sentenceCount, breakpoints, threshold, null));
  }

  private static Result standard(List<Block> blocks, ChunkOptions options, Stats stats) {
    return new Result(BlockPacker.pack(blocks, options), stats);
  }

  // ---- 1. segments and sentences -------------------------------------------------------------

  private static List<Segment> segments(List<Block> blocks, ChunkOptions options) {
    List<Segment> result = new ArrayList<>();
    Run run = null;
    List<Block> other = new ArrayList<>();
    for (Block block : blocks) {
      if (block.text().isBlank()) continue;
      if (block.kind() == Block.Kind.PROSE) {
        if (!other.isEmpty()) { result.add(new Other(other)); other = new ArrayList<>(); }
        if (run == null || !run.path.equals(block.headingPath())) {
          run = new Run(block.headingPath());
          result.add(new Prose(run));
        }
        addSentences(run, block.text(), options.size());
      } else {
        run = null;
        other.add(block);
      }
    }
    if (!other.isEmpty()) result.add(new Other(other));
    return result;
  }

  static void addSentences(Run run, String paragraph, int maxLength) {
    BreakIterator it = BreakIterator.getSentenceInstance(Locale.ROOT);
    it.setText(paragraph);
    boolean first = true;
    for (int start = it.first(), end = it.next(); end != BreakIterator.DONE; start = end, end = it.next()) {
      String sentence = paragraph.substring(start, end).strip();
      if (sentence.isEmpty()) continue;
      if (sentence.length() > maxLength) {
        // A "sentence" that cannot fit a chunk (no punctuation, a long table row) is cut like long prose.
        for (String piece : TextSplitter.prose(sentence, maxLength, 0)) {
          run.sentences.add(new Sentence(piece, first));
          first = false;
        }
      } else {
        run.sentences.add(new Sentence(sentence, first));
        first = false;
      }
    }
  }

  // ---- 2. embeddings and distances -----------------------------------------------------------

  private static void embedRuns(List<Segment> segments, SentenceEmbedder embedder) throws Exception {
    List<String> texts = new ArrayList<>();
    for (Segment s : segments) if (s instanceof Prose p) for (Sentence x : p.run().sentences) texts.add(x.text());
    List<float[]> vectors = new ArrayList<>(texts.size());
    for (int from = 0; from < texts.size(); from += BATCH) {
      List<String> batch = texts.subList(from, Math.min(texts.size(), from + BATCH));
      List<float[]> part = embedder.embed(batch);
      if (part == null || part.size() != batch.size()) throw new IllegalStateException("Embedding count mismatch");
      vectors.addAll(part);
    }
    int at = 0;
    for (Segment s : segments) {
      if (!(s instanceof Prose p)) continue;
      Run run = p.run();
      int n = run.sentences.size();
      run.distances = new double[Math.max(0, n - 1)];
      for (int i = 0; i + 1 < n; i++) run.distances[i] = distance(vectors.get(at + i), vectors.get(at + i + 1));
      at += n;
    }
  }

  /** Cosine distance; robust against vectors that are not exactly normalized. */
  static double distance(float[] a, float[] b) {
    double dot = 0, na = 0, nb = 0;
    int n = Math.min(a.length, b.length);
    for (int i = 0; i < n; i++) { dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i]; }
    if (na == 0 || nb == 0) return 1;
    return Math.max(0, 1 - dot / Math.sqrt(na * nb));
  }

  // ---- 3. grouping ---------------------------------------------------------------------------

  /** A group of sentences [from, to) of a run. */
  private record Group(int from, int to) {}

  private static int chunkRun(Run run, double threshold, ChunkOptions options, int minChars,
      List<ChunkDraft> out) {
    int n = run.sentences.size();
    List<Group> groups = new ArrayList<>();
    int breakpoints = 0, start = 0;
    for (int i = 0; i + 1 < n; i++) {
      if (run.distances[i] > threshold) {
        groups.add(new Group(start, i + 1));
        start = i + 1;
        breakpoints++;
      }
    }
    groups.add(new Group(start, n));

    List<Group> sized = new ArrayList<>();
    for (Group g : groups) split(run, g, options.size(), sized);
    List<Group> merged = merge(run, sized, options.size(), minChars);

    String heading = BlockPacker.heading(run.path);
    boolean firstOfRun = true;
    for (Group g : merged) {
      boolean startsParagraph = run.sentences.get(g.from()).paragraphStart();
      out.add(new ChunkDraft(heading, text(run, g), ChunkDraft.TEXT, null, null, null, null,
          firstOfRun || startsParagraph ? 0 : TextSplitter.CONTINUES));
      firstOfRun = false;
    }
    return breakpoints;
  }

  /** Splits at the largest internal distance until every part fits. */
  private static void split(Run run, Group g, int size, List<Group> out) {
    if (g.to() - g.from() < 2 || text(run, g).length() <= size) { out.add(g); return; }
    int best = g.from();
    double max = -1;
    for (int i = g.from(); i < g.to() - 1; i++) {
      if (run.distances[i] > max) { max = run.distances[i]; best = i; }
    }
    split(run, new Group(g.from(), best + 1), size, out);
    split(run, new Group(best + 1, g.to()), size, out);
  }

  /** Merges groups below {@code minChars} into the neighbour across the smaller distance. */
  private static List<Group> merge(Run run, List<Group> groups, int size, int minChars) {
    List<Group> list = new ArrayList<>(groups);
    boolean changed = true;
    while (changed && list.size() > 1) {
      changed = false;
      for (int i = 0; i < list.size(); i++) {
        Group g = list.get(i);
        if (text(run, g).length() >= minChars) continue;
        double left = i > 0 ? run.distances[g.from() - 1] : Double.MAX_VALUE;
        double right = i + 1 < list.size() ? run.distances[g.to() - 1] : Double.MAX_VALUE;
        int target = left <= right ? i - 1 : i + 1;
        if (target < 0 || target >= list.size()) continue;
        Group other = list.get(target);
        Group joined = new Group(Math.min(g.from(), other.from()), Math.max(g.to(), other.to()));
        if (text(run, joined).length() > size) {
          // The closer neighbour is full; try the other side before giving up.
          int alt = target == i - 1 ? i + 1 : i - 1;
          if (alt < 0 || alt >= list.size()) continue;
          other = list.get(alt);
          joined = new Group(Math.min(g.from(), other.from()), Math.max(g.to(), other.to()));
          if (text(run, joined).length() > size) continue;
          target = alt;
        }
        list.set(Math.min(i, target), joined);
        list.remove(Math.max(i, target));
        changed = true;
        break;
      }
    }
    return list;
  }

  private static String text(Run run, Group g) {
    StringBuilder sb = new StringBuilder();
    for (int i = g.from(); i < g.to(); i++) {
      Sentence s = run.sentences.get(i);
      if (i > g.from()) sb.append(s.paragraphStart() ? "\n\n" : " ");
      sb.append(s.text());
    }
    return sb.toString();
  }
}
