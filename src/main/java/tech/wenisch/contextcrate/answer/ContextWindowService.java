package tech.wenisch.contextcrate.answer;

import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tech.wenisch.contextcrate.domain.DocumentChunk;
import tech.wenisch.contextcrate.metrics.CrateMetrics;
import tech.wenisch.contextcrate.repository.DocumentChunkRepository;

/**
 * Context window: a matched chunk is expanded with the chunks before and after it in its document.
 * Chunks are numbered consecutively per document ({@link DocumentChunk#getOrdinal() the index}); a
 * window of {@code n} adds the indexes {@code index-n .. index+n}. The chunks are joined in index order
 * and the overlap of the chunking is removed, so the model reads the original passage once.
 */
@Service
public class ContextWindowService {
  private static final Logger log = LoggerFactory.getLogger(ContextWindowService.class);
  public static final int MAX_RADIUS = 5;

  /** A matched chunk: where it is in the input list (its rank), and where it sits in its document. */
  public record Anchor(UUID chunkId, UUID documentId, Integer index) {}

  /**
   * @param primary position, in the input list, of the best ranked matched chunk of this window
   * @param from first chunk index of the window; {@code to} the last one
   * @param chunks number of chunks the window consists of
   * @param added neighbour chunks that were not matched themselves
   */
  public record Window(int primary, UUID documentId, int from, int to, int chunks, int added, String text, List<UUID> chunkIds) {}

  /** A chunk with its window, for the fetch-by-index API. */
  public record Fetched(UUID documentId, int index, int from, int to, int chunkCount, String text) {}

  private final DocumentChunkRepository chunks;
  private CrateMetrics metrics = CrateMetrics.noop();

  public ContextWindowService(DocumentChunkRepository chunks) { this.chunks = chunks; }

  @Autowired(required = false)
  public void setMetrics(CrateMetrics metrics) { this.metrics = metrics; }

  /**
   * Windows for the anchors, best ranked first (input order). Anchors without an index are returned
   * as they are by the caller; this method only returns windows that could be built.
   */
  @Transactional(readOnly = true)
  public List<Window> expand(UUID crateId, List<Anchor> anchors, int radius) {
    int n = Math.max(0, Math.min(radius, MAX_RADIUS));
    Map<UUID, List<Integer>> byDocument = new LinkedHashMap<>();
    for (int i = 0; i < anchors.size(); i++) {
      var a = anchors.get(i);
      if (a.documentId() == null || a.index() == null) continue;
      byDocument.computeIfAbsent(a.documentId(), d -> new ArrayList<>()).add(i);
    }
    List<Window> windows = new ArrayList<>();
    for (var entry : byDocument.entrySet()) {
      UUID documentId = entry.getKey();
      List<Integer> positions = entry.getValue();
      // Ranges around every matched chunk, merged when they overlap or touch.
      List<int[]> ranges = new ArrayList<>(); // {from, to, primary position}
      positions.stream().sorted(Comparator.comparingInt(p -> anchors.get(p).index())).forEach(p -> {
        int index = anchors.get(p).index();
        int from = Math.max(0, index - n), to = index + n;
        int[] last = ranges.isEmpty() ? null : ranges.get(ranges.size() - 1);
        if (last != null && from <= last[1] + 1) {
          last[1] = Math.max(last[1], to);
          last[2] = Math.min(last[2], p);
        } else {
          ranges.add(new int[] {from, to, p});
        }
      });
      int min = ranges.stream().mapToInt(r -> r[0]).min().orElse(0), max = ranges.stream().mapToInt(r -> r[1]).max().orElse(0);
      List<DocumentChunk> loaded = chunks.findByDocumentIdAndCrateIdAndOrdinalBetweenOrderByOrdinal(documentId, crateId, min, max);
      Set<UUID> matched = new HashSet<>();
      for (int p : positions) matched.add(anchors.get(p).chunkId());
      for (int[] range : ranges) {
        List<DocumentChunk> part = new ArrayList<>();
        for (DocumentChunk chunk : loaded) {
          if (chunk.getOrdinal() < range[0] || chunk.getOrdinal() > range[1]) continue;
          // Image descriptions are separate chunks and not part of the running text, unless they were matched.
          if ("image".equals(chunk.getKind()) && !matched.contains(chunk.getId())) continue;
          part.add(chunk);
        }
        if (part.isEmpty()) continue;
        long hits = part.stream().filter(c -> matched.contains(c.getId())).count();
        windows.add(new Window(range[2], documentId, range[0], range[1], part.size(), (int) (part.size() - hits),
            ChunkJoiner.render(part), part.stream().map(DocumentChunk::getId).toList()));
      }
    }
    windows.sort(Comparator.comparingInt(Window::primary));
    return windows;
  }

  /** One chunk by its index, with {@code before} and {@code after} neighbours; {@code null} for an unknown index. */
  @Transactional(readOnly = true)
  public Fetched fetch(UUID crateId, UUID documentId, int index, int before, int after) {
    int b = Math.max(0, Math.min(before, MAX_RADIUS)), a = Math.max(0, Math.min(after, MAX_RADIUS));
    List<DocumentChunk> found = chunks.findByDocumentIdAndCrateIdAndOrdinalBetweenOrderByOrdinal(documentId, crateId, Math.max(0, index - b), index + a);
    if (found.stream().noneMatch(c -> c.getOrdinal() == index)) return null;
    return new Fetched(documentId, index, found.get(0).getOrdinal(), found.get(found.size() - 1).getOrdinal(), found.size(), ChunkJoiner.render(found));
  }

  void fallback(UUID crateId, String reason, Exception e) {
    log.warn("Context window unavailable; using the matched chunks only. crateId={}: {}", crateId, e == null ? reason : e.getMessage());
    metrics.count("answer.context_window.fallback", crateId, "reason", reason);
  }
}
