package tech.wenisch.contextcrate.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import tech.wenisch.contextcrate.domain.DocumentChunk;
import tech.wenisch.contextcrate.embedding.EmbeddingInputTooLargeException;
import tech.wenisch.contextcrate.storage.Hashing;
import tech.wenisch.contextcrate.index.SearchIndex;
import tech.wenisch.contextcrate.repository.*;

@Service
public class DocumentIndexer {
  private final NormalizedDocumentRepository documents;
  private final DocumentChunkRepository chunks;
  private final SearchIndex index;
  private final CrateIndexGenerationRepository generations;
  private final ExtractionService extraction;
  private final RetrievalPreparationService retrieval;

  public DocumentIndexer(
      NormalizedDocumentRepository documents, DocumentChunkRepository chunks, SearchIndex index,
      CrateIndexGenerationRepository generations, ExtractionService extraction,
      RetrievalPreparationService retrieval) {
    this.documents = documents;
    this.chunks = chunks;
    this.index = index;
    this.generations = generations;
    this.extraction = extraction;
    this.retrieval = retrieval;
  }

  private tech.wenisch.contextcrate.metrics.CrateMetrics cm = tech.wenisch.contextcrate.metrics.CrateMetrics.noop();
  @org.springframework.beans.factory.annotation.Autowired(required = false)
  public void setMetrics(tech.wenisch.contextcrate.metrics.CrateMetrics metrics) { this.cm = metrics; }

  @Transactional
  public void index(PipelinePayload payload) throws Exception {
    long started = System.nanoTime();
    var document = documents.findById(payload.entityId()).orElseThrow();
    try {
      indexDocument(payload, document);
    } finally {
      cm.time("ingestion.index.duration", document.getCrateId(), System.nanoTime() - started);
    }
  }

  private void indexDocument(PipelinePayload payload, tech.wenisch.contextcrate.domain.NormalizedDocument document) throws Exception {
    if (payload.crateId() != null && !payload.crateId().equals(document.getCrateId()))
      throw new IllegalArgumentException("Pipeline message crosses crate boundary");
    for (var previous : documents.findByCrateIdAndSourceIdAndIdentityUriAndCurrentVersionFalse(
        document.getCrateId(), document.getSourceId(), document.getIdentityUri()))
      index.delete(document.getCrateId(), previous.getId());
    for (int attempt=0;;attempt++) try {
      var current=chunks.findByDocumentIdOrderByOrdinal(document.getId());
      var records=retrieval.prepare(document.getCrateId(),current);
      index.upsert(document, records);
      for (var generation : generations.findByCrateIdAndStatus(
          document.getCrateId(), tech.wenisch.contextcrate.domain.CrateIndexGeneration.Status.BUILDING))
        index.upsertGeneration(document.getCrateId(), generation.getGeneration(), document, records);
      break;
    } catch (Exception error) {
      EmbeddingInputTooLargeException limit=findLimit(error);
      if(limit==null || attempt>=8) throw error;
      cm.count("ingestion.embedding.rechunks", document.getCrateId());
      rechunk(document, limit.suggestedMaximumCharacters());
    }
    document.indexed();
    documents.save(document);
    cm.count("ingestion.documents.indexed", document.getCrateId());
  }
  private void rechunk(tech.wenisch.contextcrate.domain.NormalizedDocument document,int size) {
    List<DocumentChunk> replacement=new ArrayList<>();int ordinal=0;
    for(var old:chunks.findByDocumentIdOrderByOrdinal(document.getId())) for(String part:old.isCode()?tech.wenisch.contextcrate.service.chunking.TextSplitter.lines(old.getContent(),size):split(old.getContent(),size)) {
      var chunk=new DocumentChunk(UUID.nameUUIDFromBytes(("chunk:"+document.getId()+":"+ordinal).getBytes(java.nio.charset.StandardCharsets.UTF_8)),document.getId(),ordinal++,old.getHeading(),part,Hashing.sha256(part));
      chunk.describe(old.getKind(),old.getLanguage(),old.getSymbol(),old.getStartLine(),null);chunk.assignCrate(document.getCrateId());replacement.add(chunk);
    }
    extraction.rebuildDocument(document.getCrateId(),document.getId());
    chunks.deleteByDocumentId(document.getId());
    chunks.saveAll(replacement);
  }
  private static List<String> split(String text,int size){List<String> out=new ArrayList<>();for(int start=0;start<text.length();){int end=Math.min(text.length(),start+size);if(end<text.length()){int boundary=Math.max(text.lastIndexOf(' ',end),text.lastIndexOf('\n',end));if(boundary>start+size/2)end=boundary;}String part=text.substring(start,end).trim();if(!part.isBlank())out.add(part);if(end>=text.length())break;start=Math.max(start+1,end);}return out;}
  private static EmbeddingInputTooLargeException findLimit(Throwable error){for(Throwable value=error;value!=null;value=value.getCause())if(value instanceof EmbeddingInputTooLargeException limit)return limit;return null;}
}
