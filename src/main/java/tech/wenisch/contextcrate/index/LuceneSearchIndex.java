package tech.wenisch.contextcrate.index;

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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tech.wenisch.contextcrate.config.ContextCrateProperties;
import tech.wenisch.contextcrate.domain.NormalizedDocument;
import tech.wenisch.contextcrate.embedding.EmbeddingProvider;
import tech.wenisch.contextcrate.reranking.RerankingProvider;
import tech.wenisch.contextcrate.reranking.DisabledRerankingProvider;
import tech.wenisch.contextcrate.repository.CrateRepository;

@Component
@ConditionalOnProperty(name = "contextcrate.index.backend", havingValue = "lucene", matchIfMissing = true)
public class LuceneSearchIndex implements SearchIndex {
  private static final int MAX_OPEN_WRITERS = 32;
  private final Path root; private final StandardAnalyzer analyzer = new StandardAnalyzer();
  private final EmbeddingProvider embeddings; private final RerankingProvider reranking; private final ContextCrateProperties.Retrieval retrieval;
  private final Map<String, IndexWriter> writers = new LinkedHashMap<>();
  private final CrateRepository crates;
  @Autowired
  public LuceneSearchIndex(ContextCrateProperties properties, EmbeddingProvider embeddings,RerankingProvider reranking,CrateRepository crates) {
    root = properties.index().path().toAbsolutePath().normalize(); this.embeddings=embeddings;this.reranking=reranking;this.crates=crates; retrieval=properties.retrieval();
  }
  /** Compatibility constructor for direct callers that predate reranking. */
  public LuceneSearchIndex(ContextCrateProperties properties, EmbeddingProvider embeddings,CrateRepository crates) {
    this(properties,embeddings,new DisabledRerankingProvider(),crates);
  }
  @Override public synchronized void initialize(UUID crateId) throws IOException {
    writer(crateId,generation(crateId));
  }
  private int generation(UUID crateId){return crates.findById(crateId).orElseThrow().getActiveIndexGeneration();}
  private static String key(UUID crateId,int generation){return crateId+":"+generation;}
  private IndexWriter writer(UUID crateId,int generation) throws IOException {
    IndexWriter existing = writers.get(key(crateId,generation)); if(existing != null) return existing;
    if (writers.size() >= MAX_OPEN_WRITERS) {
      var eldest = writers.entrySet().iterator().next();
      eldest.getValue().commit();
      eldest.getValue().close();
      writers.remove(eldest.getKey());
    }
    Path namespace = root.resolve("crates").resolve(crateId.toString()).resolve(Integer.toString(generation));
    Files.createDirectories(namespace);
    IndexWriter created = new IndexWriter(FSDirectory.open(namespace),
        new IndexWriterConfig(analyzer).setOpenMode(IndexWriterConfig.OpenMode.CREATE_OR_APPEND));
    writers.put(key(crateId,generation), created); return created;
  }
  @Override public synchronized void upsert(NormalizedDocument source, List<ChunkRetrievalRecord> chunks) throws IOException, InterruptedException {
    upsertGeneration(source.getCrateId(),generation(source.getCrateId()),source,chunks);
  }
  @Override public synchronized void upsertGeneration(UUID crateId,int generation,NormalizedDocument source,List<ChunkRetrievalRecord> chunks)throws IOException,InterruptedException{
    if (!crateId.equals(source.getCrateId()))
      throw new IllegalArgumentException("Index input crosses crate boundary");
    try(var ignored=tech.wenisch.contextcrate.config.CrateContext.use(crateId)){
      upsertScoped(crateId,generation,source,chunks);
    }
  }
  private void upsertScoped(UUID crateId,int generation,NormalizedDocument source,List<ChunkRetrievalRecord> chunks)throws IOException,InterruptedException{
    IndexWriter writer=writer(crateId,generation); List<org.apache.lucene.document.Document> docs=new ArrayList<>();
    try {
      docs.add(document(source, embedding(source.getBody())));
      for(var c:chunks) docs.add(chunk(source,c,embedding(c.retrievalText())));
    } catch(Exception e) {
      throw new IOException("Could not create document embeddings: " + errorDetail(e), e);
    }
    writer.deleteDocuments(new Term("parent_id",source.getId().toString())); writer.addDocuments(docs); writer.commit();
  }
  private float[] embedding(String text) throws Exception { return embeddings.available() ? embeddings.embedDocuments(List.of(text)).getFirst() : null; }
  private static String errorDetail(Exception error) {
    StringBuilder detail = new StringBuilder();
    for (Throwable cause = error; cause != null; cause = cause.getCause()) {
      String message = cause.getMessage();
      if (message == null || message.isBlank()) continue;
      if (!detail.isEmpty()) detail.append("; caused by: ");
      detail.append(message);
      if (detail.length() >= 3500) break;
    }
    return detail.isEmpty() ? error.getClass().getSimpleName() : detail.toString();
  }
  private static org.apache.lucene.document.Document document(NormalizedDocument d, float[] vector) { var doc=new org.apache.lucene.document.Document(); common(doc,d); doc.add(new StringField("kind","document",Field.Store.YES)); doc.add(new StoredField("body",d.getBody())); doc.add(new TextField("text",d.getBody(),Field.Store.NO)); vector(doc,vector); return doc; }
  private static org.apache.lucene.document.Document chunk(NormalizedDocument p,ChunkRetrievalRecord c,float[] vector){var doc=new org.apache.lucene.document.Document();common(doc,p);doc.add(new StringField("kind","chunk",Field.Store.YES));doc.add(new StringField("id",c.chunkId().toString(),Field.Store.YES));doc.add(new StringField("record_id",c.recordId().toString(),Field.Store.YES));doc.add(new StringField("proposition",Boolean.toString(c.proposition()),Field.Store.YES));doc.add(new IntPoint("ordinal",c.ordinal()));doc.add(new StoredField("ordinal_stored",c.ordinal()));if(c.heading()!=null)doc.add(new TextField("heading",c.heading(),Field.Store.YES));doc.add(new TextField("text",c.retrievalText(),Field.Store.YES));doc.add(new StoredField("source_text",c.sourceContent()));vector(doc,vector);return doc;}
  private static void vector(org.apache.lucene.document.Document doc,float[] vector) { if(vector!=null) doc.add(new KnnFloatVectorField("embedding",vector,VectorSimilarityFunction.COSINE)); }
  private static void common(org.apache.lucene.document.Document doc,NormalizedDocument d) { doc.add(new StringField("parent_id",d.getId().toString(),Field.Store.YES)); doc.add(new StringField("run_id",d.getRunId().toString(),Field.Store.YES)); doc.add(new StringField("url",d.getSourceUri(),Field.Store.YES)); doc.add(new TextField("url_text",d.getSourceUri(),Field.Store.NO)); if(d.getTitle()!=null)doc.add(new TextField("title",d.getTitle(),Field.Store.YES)); if(d.getLanguage()!=null)doc.add(new StringField("language",d.getLanguage(),Field.Store.YES)); doc.add(new StringField("content_hash",d.getContentHash(),Field.Store.YES)); }
  @Override public synchronized void delete(UUID crateId,UUID id)throws IOException { var writer=writer(crateId,generation(crateId));writer.deleteDocuments(new Term("parent_id",id.toString()));writer.commit(); }
  @Override public synchronized SearchResults search(SearchRequest request)throws IOException {
    try(var ignored=tech.wenisch.contextcrate.config.CrateContext.use(request.crateId())){
      return searchScoped(request);
    }
  }
  private SearchResults searchScoped(SearchRequest request)throws IOException{
    var writer=writer(request.crateId(),generation(request.crateId())); if(request.query().isBlank())return new SearchResults(request.query(),mode(request),List.of()); writer.commit();
    String mode=mode(request); try(var reader=DirectoryReader.open(writer)) { var searcher=new IndexSearcher(reader); List<SearchHit> lexical=mode.equals("semantic")?List.of():lexical(searcher,request); List<SearchHit> semantic=mode.equals("lexical")?List.of():semantic(searcher,request);
      int candidates=RerankingSearchSupport.candidateLimit(request,reranking,retrieval.candidateLimit());
      List<SearchHit> found=mode.equals("hybrid") ? fuse(lexical,semantic,candidates) : limit(mode.equals("lexical")?lexical:semantic,candidates);
      return new SearchResults(request.query(),mode,RerankingSearchSupport.apply(request,found,reranking));
    } catch(Exception e) { if(e instanceof IOException io)throw io; throw new IOException("Search failed",e); }
  }
  private String mode(SearchRequest r) { return r.mode()!=null?r.mode():(embeddings.available()?retrieval.defaultMode():"lexical"); }
  private List<SearchHit> lexical(IndexSearcher s,SearchRequest r)throws Exception { Query q=textQuery(r); TopDocs found=s.search(q,retrieval.candidateLimit()); return hits(s,found,r.query(),true); }
  private List<SearchHit> semantic(IndexSearcher s,SearchRequest r)throws Exception { if(!embeddings.available())throw new IllegalStateException("Semantic search requested but embeddings are unavailable"); Query filter=filters(r); TopDocs found=s.search(new KnnFloatVectorQuery("embedding",embeddings.embedQuery(r.query()),retrieval.candidateLimit(),filter),retrieval.candidateLimit()); return hits(s,found,r.query(),false); }
  private Query textQuery(SearchRequest r)throws Exception { Query text=new MultiFieldQueryParser(new String[]{"title","heading","text","url_text"},analyzer).parse(MultiFieldQueryParser.escape(r.query())); var b=new BooleanQuery.Builder().add(text,BooleanClause.Occur.MUST); addFilters(b,r); return b.build(); }
  private Query filters(SearchRequest r) { var b=new BooleanQuery.Builder(); addFilters(b,r); return b.build(); }
  private static void addFilters(BooleanQuery.Builder b,SearchRequest r){if(r.kind()!=null)b.add(new TermQuery(new Term("kind",r.kind())),BooleanClause.Occur.FILTER);if(r.runId()!=null)b.add(new TermQuery(new Term("run_id",r.runId().toString())),BooleanClause.Occur.FILTER);}
  private List<SearchHit> hits(IndexSearcher s,TopDocs found,String query,boolean lexical)throws IOException{var out=new LinkedHashMap<UUID,SearchHit>();var stored=s.storedFields();for(var score:found.scoreDocs){var hit=hit(stored.document(score.doc),score.score,query,lexical);out.putIfAbsent(hit.id(),hit);}return new ArrayList<>(out.values());}
  private static SearchHit hit(org.apache.lucene.document.Document d,float score,String q,boolean lexical){String kind=d.get("kind");UUID docId=UUID.fromString(d.get("parent_id"));UUID id=UUID.fromString(kind.equals("chunk")?d.get("id"):d.get("parent_id"));String retrieval=kind.equals("chunk")?d.get("text"):d.get("body");String source=kind.equals("chunk")?d.get("source_text"):retrieval;if(source==null)source=retrieval;Integer ordinal=kind.equals("chunk")?d.getField("ordinal_stored").numericValue().intValue():null;return new SearchHit(id,docId,UUID.fromString(d.get("run_id")),kind,d.get("title"),d.get("url"),ordinal,snippet(retrieval,q),score,lexical?score:null,lexical?null:score,null,source,retrieval);}
  private List<SearchHit> fuse(List<SearchHit>a,List<SearchHit>b,int limit){Map<UUID,SearchHit> all=new LinkedHashMap<>();Map<UUID,Float> scores=new HashMap<>();for(int i=0;i<a.size();i++){var h=a.get(i);all.put(h.id(),h);scores.merge(h.id(),1f/(retrieval.rrfConstant()+i+1),Float::sum);}for(int i=0;i<b.size();i++){var h=b.get(i);all.putIfAbsent(h.id(),h);scores.merge(h.id(),1f/(retrieval.rrfConstant()+i+1),Float::sum);}return all.values().stream().map(h->new SearchHit(h.id(),h.documentId(),h.runId(),h.kind(),h.title(),h.sourceUri(),h.chunkOrdinal(),h.snippet(),scores.get(h.id()),find(a,h.id(),true),find(b,h.id(),false),null,h.content(),h.retrievalContent())).sorted(Comparator.comparing(SearchHit::score).reversed().thenComparing(h->h.id().toString())).limit(limit).toList();}
  private static Float find(List<SearchHit>x,UUID id,boolean lexical){return x.stream().filter(h->h.id().equals(id)).map(h->lexical?h.lexicalScore():h.semanticScore()).findFirst().orElse(null);} private static List<SearchHit> limit(List<SearchHit>x,int n){return x.stream().limit(n).toList();}
  @Override public synchronized void commit(UUID crateId)throws IOException{writer(crateId,generation(crateId)).commit();}
  @Override public synchronized void commitGeneration(UUID crateId,int generation)throws IOException{writer(crateId,generation).commit();}
  @Override public synchronized IndexHealth health(UUID crateId){try{int generation=generation(crateId);var writer=writer(crateId,generation);return new IndexHealth(crateId,"lucene",true,writer.getDocStats().numDocs,"Namespace at "+root.resolve("crates").resolve(crateId.toString()).resolve(Integer.toString(generation))+"; embeddings="+embeddings.available());}catch(Exception e){return new IndexHealth(crateId,"lucene",false,0,e.getMessage());}}
  @Override public synchronized void deleteGeneration(UUID crateId,int generation)throws IOException{var open=writers.remove(key(crateId,generation));if(open!=null)open.close();deleteTree(root.resolve("crates").resolve(crateId.toString()).resolve(Integer.toString(generation)),root.resolve("crates"));}
  @Override public synchronized void deleteNamespace(UUID crateId)throws IOException{for(String writerKey:writers.keySet().stream().filter(k->k.startsWith(crateId+":")).toList()){var open=writers.remove(writerKey);if(open!=null)open.close();}deleteTree(root.resolve("crates").resolve(crateId.toString()),root.resolve("crates"));}
  private static void deleteTree(Path target,Path allowedRoot)throws IOException{target=target.normalize();allowedRoot=allowedRoot.normalize();if(!target.startsWith(allowedRoot)||target.equals(allowedRoot))throw new IOException("Unsafe index namespace");if(Files.exists(target))try(var paths=Files.walk(target)){for(var path:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}}
  private static String snippet(String text,String q){if(text==null)return "";if(text.length()<=240)return text;String lower=text.toLowerCase(Locale.ROOT);int m=lower.indexOf(q.toLowerCase(Locale.ROOT));int start=m<0?0:Math.max(0,m-100),end=Math.min(text.length(),start+240);return(start>0?"...":"")+text.substring(start,end)+(end<text.length()?"...":"");}
  @PreDestroy @Override public synchronized void close()throws IOException{IOException failure=null;for(var writer:writers.values())try{writer.commit();writer.close();}catch(IOException e){failure=e;}writers.clear();if(failure!=null)throw failure;}
}
