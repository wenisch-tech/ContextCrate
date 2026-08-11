package tech.wenisch.contextcrate.index;
import java.util.UUID;
import tech.wenisch.contextcrate.domain.DocumentChunk;
public record ChunkRetrievalRecord(UUID recordId,UUID chunkId,int ordinal,String heading,String retrievalText,String sourceContent,String contentHash,boolean proposition){
  public static ChunkRetrievalRecord standard(DocumentChunk c){return new ChunkRetrievalRecord(c.getId(),c.getId(),c.getOrdinal(),c.getHeading(),c.getContent(),c.getContent(),c.getContentHash(),false);}
}
