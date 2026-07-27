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

  public DocumentIndexer(
      NormalizedDocumentRepository documents, DocumentChunkRepository chunks, SearchIndex index,
      CrateIndexGenerationRepository generations, ExtractionService extraction) {
    this.documents = documents;
    this.chunks = chunks;
    this.index = index;
    this.generations = generations;
    this.extraction = extraction;
  }

  @Transactional
  public void index(PipelinePayload payload) throws Exception {
    var document = documents.findById(payload.entityId()).orElseThrow();
    if (payload.crateId() != null && !payload.crateId().equals(document.getCrateId()))
      throw new IllegalArgumentException("Pipeline message crosses crate boundary");
    for (var previous : documents.findByCrateIdAndSourceIdAndIdentityUriAndCurrentVersionFalse(
        document.getCrateId(), document.getSourceId(), document.getIdentityUri()))
      index.delete(document.getCrateId(), previous.getId());
    for (int attempt=0;;attempt++) try {
      var current=chunks.findByDocumentIdOrderByOrdinal(document.getId());
      index.upsert(document, current);
      for (var generation : generations.findByCrateIdAndStatus(
          document.getCrateId(), tech.wenisch.contextcrate.domain.CrateIndexGeneration.Status.BUILDING))
        index.upsertGeneration(document.getCrateId(), generation.getGeneration(), document, current);
      break;
    } catch (Exception error) {
      EmbeddingInputTooLargeException limit=findLimit(error);
      if(limit==null || attempt>=8) throw error;
      rechunk(document, limit.suggestedMaximumCharacters());
    }
    document.indexed();
    documents.save(document);
  }
  private void rechunk(tech.wenisch.contextcrate.domain.NormalizedDocument document,int size) {
    List<DocumentChunk> replacement=new ArrayList<>();int ordinal=0;
    for(var old:chunks.findByDocumentIdOrderByOrdinal(document.getId())) for(String part:split(old.getContent(),size)) {
      var chunk=new DocumentChunk(UUID.nameUUIDFromBytes(("chunk:"+document.getId()+":"+ordinal).getBytes(java.nio.charset.StandardCharsets.UTF_8)),document.getId(),ordinal++,old.getHeading(),part,Hashing.sha256(part));
      chunk.assignCrate(document.getCrateId());replacement.add(chunk);
    }
    extraction.rebuildDocument(document.getCrateId(),document.getId());
    chunks.deleteByDocumentId(document.getId());
    chunks.saveAll(replacement);
  }
  private static List<String> split(String text,int size){List<String> out=new ArrayList<>();for(int start=0;start<text.length();){int end=Math.min(text.length(),start+size);if(end<text.length()){int boundary=Math.max(text.lastIndexOf(' ',end),text.lastIndexOf('\n',end));if(boundary>start+size/2)end=boundary;}String part=text.substring(start,end).trim();if(!part.isBlank())out.add(part);if(end>=text.length())break;start=Math.max(start+1,end);}return out;}
  private static EmbeddingInputTooLargeException findLimit(Throwable error){for(Throwable value=error;value!=null;value=value.getCause())if(value instanceof EmbeddingInputTooLargeException limit)return limit;return null;}
}
