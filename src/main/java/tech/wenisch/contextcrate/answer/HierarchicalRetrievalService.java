package tech.wenisch.contextcrate.answer;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import tech.wenisch.contextcrate.index.SearchIndex;
import tech.wenisch.contextcrate.index.SearchIndex.SearchRequest;
import tech.wenisch.contextcrate.metrics.CrateMetrics;
import tech.wenisch.contextcrate.repository.DocumentChunkRepository;
import tech.wenisch.contextcrate.repository.SectionSummaryRepository;

/**
 * Query side of hierarchical retrieval. The question is first searched in the summary level
 * (one vector per document section); the sections of the best hits are turned into the set of their
 * chunk ids, which the normal chunk search is then restricted to.
 *
 * <p>Fail-open: any error, no summary hit or an empty scope returns {@code null}, which means
 * "search all chunks as usual".
 */
@Service
public class HierarchicalRetrievalService {
  private static final Logger log = LoggerFactory.getLogger(HierarchicalRetrievalService.class);
  private final SearchIndex index;
  private final SectionSummaryRepository sections;
  private final DocumentChunkRepository chunks;
  private CrateMetrics metrics = CrateMetrics.noop();

  public HierarchicalRetrievalService(SearchIndex index, SectionSummaryRepository sections, DocumentChunkRepository chunks) {
    this.index = index; this.sections = sections; this.chunks = chunks;
  }

  @Autowired(required = false)
  public void setMetrics(CrateMetrics metrics) { this.metrics = metrics; }

  /**
   * @param chunkSearch the chunk search the answer would run (mode, HyDE text and fusion weight are reused)
   * @param topK number of sections to take from the summary level
   * @return ids of the chunks of the best sections, or {@code null} when the search is not narrowed
   */
  public Set<UUID> scope(SearchRequest chunkSearch, int topK) {
    UUID crateId = chunkSearch.crateId();
    try {
      var summarySearch = new SearchRequest(crateId, chunkSearch.query(), topK, chunkSearch.runId(), SearchIndex.SUMMARY,
          chunkSearch.mode(), chunkSearch.vectorQuery(), chunkSearch.vectorWeight());
      var hits = index.search(summarySearch).hits();
      Set<UUID> sectionIds = new HashSet<>();
      for (var hit : hits) sectionIds.add(hit.id());
      if (sectionIds.isEmpty()) { metrics.count("answer.hierarchical", crateId, "outcome", "no-summary"); return null; }
      Set<UUID> chunkIds = new HashSet<>();
      for (var section : sections.findByCrateIdAndIdIn(crateId, sectionIds))
        for (var chunk : chunks.findByDocumentIdAndCrateIdAndOrdinalBetweenOrderByOrdinal(
            section.getDocumentId(), crateId, section.getFirstOrdinal(), section.getLastOrdinal()))
          chunkIds.add(chunk.getId());
      if (chunkIds.isEmpty()) { metrics.count("answer.hierarchical", crateId, "outcome", "no-chunks"); return null; }
      metrics.count("answer.hierarchical", crateId, "outcome", "applied");
      metrics.summary("answer.hierarchical.sections", crateId, sectionIds.size());
      return chunkIds;
    } catch (Exception e) {
      log.warn("Hierarchical retrieval failed; searching all chunks. crateId={}: {}", crateId, e.getMessage());
      metrics.count("answer.hierarchical", crateId, "outcome", "error");
      return null;
    }
  }

  /** Records that the narrowed chunk search found nothing and the full search was used. */
  public void fellBack(UUID crateId) { metrics.count("answer.hierarchical", crateId, "outcome", "empty-scope"); }
}
