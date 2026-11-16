package tech.wenisch.contextcrate.index;
import java.util.UUID;
import tech.wenisch.contextcrate.domain.DocumentChunk;
public record ChunkRetrievalRecord(UUID recordId,UUID chunkId,int ordinal,String heading,String retrievalText,String sourceContent,String contentHash,boolean proposition,String type){
  public static final String CHUNK="chunk",PROPOSITION="proposition",QUESTION="question",SUMMARY="summary";
  public ChunkRetrievalRecord(UUID recordId,UUID chunkId,int ordinal,String heading,String retrievalText,String sourceContent,String contentHash,boolean proposition){this(recordId,chunkId,ordinal,heading,retrievalText,sourceContent,contentHash,proposition,proposition?PROPOSITION:CHUNK);}
  /** A generated question that points at its chunk: the question is what is embedded, the chunk is what a hit returns. */
  public static ChunkRetrievalRecord question(DocumentChunk c,int index,String question){
    UUID id=UUID.nameUUIDFromBytes(("question:"+c.getId()+":"+index+":"+question).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    return new ChunkRetrievalRecord(id,c.getId(),c.getOrdinal(),c.getHeading(),question,c.getContent(),c.getContentHash(),false,QUESTION);
  }
  /**
   * LLM summary of a document section. {@code chunkId} of the record is the section id, so a hit's id names the
   * section; the heading is the section's heading path and the ordinal is the ordinal of its first chunk.
   */
  public static ChunkRetrievalRecord summary(UUID sectionId,int firstOrdinal,String heading,String summary){
    UUID id=UUID.nameUUIDFromBytes(("summary:"+sectionId).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    return new ChunkRetrievalRecord(id,sectionId,firstOrdinal,heading,summary,summary,sha256(summary),false,SUMMARY);
  }
  private static String sha256(String text){try{return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));}catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
  public boolean isSummary(){return SUMMARY.equals(type);}
  public boolean isQuestion(){return QUESTION.equals(type);}
  public static ChunkRetrievalRecord standard(DocumentChunk c){return new ChunkRetrievalRecord(c.getId(),c.getId(),c.getOrdinal(),c.getHeading(),retrievalText(c),c.getContent(),c.getContentHash(),false);}
  /**
   * Standard record whose retrieval text starts with a contextual header (title, summary, section).
   * Only the retrieval text changes: the source content and its hash stay the original chunk.
   */
  public static ChunkRetrievalRecord standard(DocumentChunk c,String header){
    if(header==null||header.isBlank())return standard(c);
    return new ChunkRetrievalRecord(c.getId(),c.getId(),c.getOrdinal(),c.getHeading(),header+"\n\n"+retrievalText(c),c.getContent(),c.getContentHash(),false);
  }
  /** Code is embedded and indexed together with its language and symbol, so a query for a function name finds it. */
  public static String retrievalText(DocumentChunk c){
    if(!c.isCode()||(c.getSymbol()==null&&c.getLanguage()==null))return c.getContent();
    return "["+(c.getLanguage()==null?"code":c.getLanguage())+"] "+(c.getSymbol()==null?"":c.getSymbol())+"\n"+c.getContent();
  }
}
