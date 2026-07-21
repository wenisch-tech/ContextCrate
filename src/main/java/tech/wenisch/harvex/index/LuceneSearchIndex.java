package tech.wenisch.harvex.index;

import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.*;
import org.apache.lucene.index.*;
import org.apache.lucene.queryparser.classic.MultiFieldQueryParser;
import org.apache.lucene.search.*;
import org.apache.lucene.store.FSDirectory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tech.wenisch.harvex.config.HarvexProperties;
import tech.wenisch.harvex.domain.DocumentChunk;
import tech.wenisch.harvex.domain.NormalizedDocument;
import tech.wenisch.harvex.embedding.EmbeddingProvider;

@Component
@ConditionalOnProperty(name = "harvex.index.backend", havingValue = "lucene", matchIfMissing = true)
public class LuceneSearchIndex implements SearchIndex {
  private final Path root; private final StandardAnalyzer analyzer = new StandardAnalyzer();
  private final EmbeddingProvider embeddings; private final HarvexProperties.Retrieval retrieval; private IndexWriter writer;
  public LuceneSearchIndex(HarvexProperties properties, EmbeddingProvider embeddings) {
    root = properties.index().path().toAbsolutePath().normalize(); this.embeddings=embeddings; retrieval=properties.retrieval();
  }
  @Override public synchronized void initialize() throws IOException {
    if(writer != null) return; Files.createDirectories(root); writer=new IndexWriter(FSDirectory.open(root.resolve("v2")), new IndexWriterConfig(analyzer).setOpenMode(IndexWriterConfig.OpenMode.CREATE_OR_APPEND));
  }
  @Override public synchronized void upsert(NormalizedDocument source, List<DocumentChunk> chunks) throws IOException, InterruptedException {
    initialize(); List<org.apache.lucene.document.Document> docs=new ArrayList<>();
    try {
      docs.add(document(source, embedding(source.getBody())));
      for(var c:chunks) docs.add(chunk(source,c,embedding(c.getContent())));
    } catch(Exception e) { throw new IOException("Could not create document embeddings", e); }
    writer.deleteDocuments(new Term("parent_id",source.getId().toString())); writer.addDocuments(docs); writer.commit();
  }
  private float[] embedding(String text) throws Exception { return embeddings.available() ? embeddings.embedDocuments(List.of(text)).getFirst() : null; }
  private static org.apache.lucene.document.Document document(NormalizedDocument d, float[] vector) { var doc=new org.apache.lucene.document.Document(); common(doc,d); doc.add(new StringField("kind","document",Field.Store.YES)); doc.add(new StoredField("body",d.getBody())); doc.add(new TextField("text",d.getBody(),Field.Store.NO)); vector(doc,vector); return doc; }
  private static org.apache.lucene.document.Document chunk(NormalizedDocument p, DocumentChunk c, float[] vector) { var doc=new org.apache.lucene.document.Document(); common(doc,p); doc.add(new StringField("kind","chunk",Field.Store.YES)); doc.add(new StringField("id",c.getId().toString(),Field.Store.YES)); doc.add(new IntPoint("ordinal",c.getOrdinal())); doc.add(new StoredField("ordinal_stored",c.getOrdinal())); if(c.getHeading()!=null)doc.add(new TextField("heading",c.getHeading(),Field.Store.YES)); doc.add(new TextField("text",c.getContent(),Field.Store.YES)); vector(doc,vector); return doc; }
  private static void vector(org.apache.lucene.document.Document doc,float[] vector) { if(vector!=null) doc.add(new KnnFloatVectorField("embedding",vector,VectorSimilarityFunction.COSINE)); }
  private static void common(org.apache.lucene.document.Document doc,NormalizedDocument d) { doc.add(new StringField("parent_id",d.getId().toString(),Field.Store.YES)); doc.add(new StringField("run_id",d.getRunId().toString(),Field.Store.YES)); doc.add(new StringField("url",d.getCanonicalUrl(),Field.Store.YES)); doc.add(new TextField("url_text",d.getCanonicalUrl(),Field.Store.NO)); if(d.getTitle()!=null)doc.add(new TextField("title",d.getTitle(),Field.Store.YES)); if(d.getLanguage()!=null)doc.add(new StringField("language",d.getLanguage(),Field.Store.YES)); doc.add(new StringField("content_hash",d.getContentHash(),Field.Store.YES)); }
  @Override public synchronized void delete(UUID id)throws IOException { initialize();writer.deleteDocuments(new Term("parent_id",id.toString()));writer.commit(); }
  @Override public synchronized SearchResults search(SearchRequest request)throws IOException {
    initialize(); if(request.query().isBlank())return new SearchResults(request.query(),mode(request),List.of()); writer.commit();
    String mode=mode(request); try(var reader=DirectoryReader.open(writer)) { var searcher=new IndexSearcher(reader); List<SearchHit> lexical=mode.equals("semantic")?List.of():lexical(searcher,request); List<SearchHit> semantic=mode.equals("lexical")?List.of():semantic(searcher,request);
      return new SearchResults(request.query(),mode, mode.equals("hybrid") ? fuse(lexical,semantic,request.limit()) : limit(mode.equals("lexical")?lexical:semantic,request.limit()));
    } catch(Exception e) { if(e instanceof IOException io)throw io; throw new IOException("Search failed",e); }
  }
  private String mode(SearchRequest r) { return r.mode()!=null?r.mode():(embeddings.available()?retrieval.defaultMode():"lexical"); }
  private List<SearchHit> lexical(IndexSearcher s,SearchRequest r)throws Exception { Query q=textQuery(r); TopDocs found=s.search(q,retrieval.candidateLimit()); return hits(s,found,r.query(),true); }
  private List<SearchHit> semantic(IndexSearcher s,SearchRequest r)throws Exception { if(!embeddings.available())throw new IllegalStateException("Semantic search requested but embeddings are unavailable"); Query filter=filters(r); TopDocs found=s.search(new KnnFloatVectorQuery("embedding",embeddings.embedQuery(r.query()),retrieval.candidateLimit(),filter),retrieval.candidateLimit()); return hits(s,found,r.query(),false); }
  private Query textQuery(SearchRequest r)throws Exception { Query text=new MultiFieldQueryParser(new String[]{"title","heading","text","url_text"},analyzer).parse(MultiFieldQueryParser.escape(r.query())); var b=new BooleanQuery.Builder().add(text,BooleanClause.Occur.MUST); addFilters(b,r); return b.build(); }
  private Query filters(SearchRequest r) { var b=new BooleanQuery.Builder(); addFilters(b,r); return b.build(); }
  private static void addFilters(BooleanQuery.Builder b,SearchRequest r){if(r.kind()!=null)b.add(new TermQuery(new Term("kind",r.kind())),BooleanClause.Occur.FILTER);if(r.runId()!=null)b.add(new TermQuery(new Term("run_id",r.runId().toString())),BooleanClause.Occur.FILTER);}
  private List<SearchHit> hits(IndexSearcher s,TopDocs found,String query,boolean lexical)throws IOException {var out=new ArrayList<SearchHit>();var stored=s.storedFields();for(var score:found.scoreDocs){var doc=stored.document(score.doc);out.add(hit(doc,score.score,query,lexical));}return out;}
  private static SearchHit hit(org.apache.lucene.document.Document d,float score,String q,boolean lexical){String kind=d.get("kind");UUID docId=UUID.fromString(d.get("parent_id"));UUID id=UUID.fromString(kind.equals("chunk")?d.get("id"):d.get("parent_id"));String text=kind.equals("chunk")?d.get("text"):d.get("body");Integer ordinal=kind.equals("chunk")?d.getField("ordinal_stored").numericValue().intValue():null;return new SearchHit(id,docId,UUID.fromString(d.get("run_id")),kind,d.get("title"),d.get("url"),ordinal,snippet(text,q),score,lexical?score:null,lexical?null:score);}
  private List<SearchHit> fuse(List<SearchHit>a,List<SearchHit>b,int limit){Map<UUID,SearchHit> all=new LinkedHashMap<>();Map<UUID,Float> scores=new HashMap<>();for(int i=0;i<a.size();i++){var h=a.get(i);all.put(h.id(),h);scores.merge(h.id(),1f/(retrieval.rrfConstant()+i+1),Float::sum);}for(int i=0;i<b.size();i++){var h=b.get(i);all.putIfAbsent(h.id(),h);scores.merge(h.id(),1f/(retrieval.rrfConstant()+i+1),Float::sum);}return all.values().stream().map(h->new SearchHit(h.id(),h.documentId(),h.runId(),h.kind(),h.title(),h.canonicalUrl(),h.chunkOrdinal(),h.snippet(),scores.get(h.id()),find(a,h.id(),true),find(b,h.id(),false))).sorted(Comparator.comparing(SearchHit::score).reversed().thenComparing(h->h.id().toString())).limit(limit).toList();}
  private static Float find(List<SearchHit>x,UUID id,boolean lexical){return x.stream().filter(h->h.id().equals(id)).map(h->lexical?h.lexicalScore():h.semanticScore()).findFirst().orElse(null);} private static List<SearchHit> limit(List<SearchHit>x,int n){return x.stream().limit(n).toList();}
  @Override public synchronized void commit()throws IOException{initialize();writer.commit();} @Override public synchronized IndexHealth health(){try{initialize();return new IndexHealth("lucene",true,writer.getDocStats().numDocs,"Vector index at "+root.resolve("v2")+"; embeddings="+embeddings.available());}catch(Exception e){return new IndexHealth("lucene",false,0,e.getMessage());}}
  private static String snippet(String text,String q){if(text==null)return "";if(text.length()<=240)return text;String lower=text.toLowerCase(Locale.ROOT);int m=lower.indexOf(q.toLowerCase(Locale.ROOT));int start=m<0?0:Math.max(0,m-100),end=Math.min(text.length(),start+240);return(start>0?"...":"")+text.substring(start,end)+(end<text.length()?"...":"");}
  @PreDestroy @Override public synchronized void close()throws IOException{if(writer!=null){writer.commit();writer.close();writer=null;}}
}
