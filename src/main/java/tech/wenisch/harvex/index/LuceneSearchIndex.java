package tech.wenisch.harvex.index;

import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.file.*;
import java.util.List;
import java.util.UUID;
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

@Component
@ConditionalOnProperty(name = "harvex.index.backend", havingValue = "lucene", matchIfMissing = true)
public class LuceneSearchIndex implements SearchIndex {
  private final Path root;
  private final StandardAnalyzer analyzer = new StandardAnalyzer();
  private IndexWriter writer;

  public LuceneSearchIndex(HarvexProperties properties) {
    root = properties.index().path().toAbsolutePath().normalize();
  }

  @Override
  public synchronized void initialize() throws IOException {
    if (writer != null) return;
    Files.createDirectories(root);
    var dir = FSDirectory.open(root.resolve("v1"));
    writer =
        new IndexWriter(
            dir,
          new IndexWriterConfig(analyzer)
                .setOpenMode(IndexWriterConfig.OpenMode.CREATE_OR_APPEND));
  }

  @Override
  public synchronized void upsert(NormalizedDocument source, List<DocumentChunk> chunks)
      throws IOException {
    initialize();
    var docs = new java.util.ArrayList<org.apache.lucene.document.Document>();
    docs.add(document(source));
    for (var chunk : chunks) docs.add(chunk(source, chunk));
    writer.deleteDocuments(new Term("parent_id", source.getId().toString()));
    writer.addDocuments(docs);
    writer.commit();
  }

  private static org.apache.lucene.document.Document document(NormalizedDocument d) {
    var doc = new org.apache.lucene.document.Document();
    common(doc, d);
    doc.add(new StringField("kind", "document", Field.Store.YES));
    doc.add(new StoredField("body", d.getBody()));
    doc.add(new TextField("text", d.getBody(), Field.Store.NO));
    return doc;
  }

  private static org.apache.lucene.document.Document chunk(
      NormalizedDocument parent, DocumentChunk c) {
    var doc = new org.apache.lucene.document.Document();
    common(doc, parent);
    doc.add(new StringField("kind", "chunk", Field.Store.YES));
    doc.add(new StringField("id", c.getId().toString(), Field.Store.YES));
    doc.add(new IntPoint("ordinal", c.getOrdinal()));
    doc.add(new StoredField("ordinal_stored", c.getOrdinal()));
    if (c.getHeading() != null) doc.add(new TextField("heading", c.getHeading(), Field.Store.YES));
    doc.add(new TextField("text", c.getContent(), Field.Store.YES));
    return doc;
  }

  private static void common(org.apache.lucene.document.Document doc, NormalizedDocument d) {
    doc.add(new StringField("parent_id", d.getId().toString(), Field.Store.YES));
    doc.add(new StringField("run_id", d.getRunId().toString(), Field.Store.YES));
    doc.add(new StringField("url", d.getCanonicalUrl(), Field.Store.YES));
    doc.add(new TextField("url_text", d.getCanonicalUrl(), Field.Store.NO));
    if (d.getTitle() != null) doc.add(new TextField("title", d.getTitle(), Field.Store.YES));
    if (d.getLanguage() != null)
      doc.add(new StringField("language", d.getLanguage(), Field.Store.YES));
    doc.add(new StringField("content_hash", d.getContentHash(), Field.Store.YES));
  }

  @Override
  public synchronized void delete(UUID id) throws IOException {
    initialize();
    writer.deleteDocuments(new Term("parent_id", id.toString()));
    writer.commit();
  }

  @Override
  public synchronized SearchResults search(SearchRequest request) throws IOException {
    initialize();
    if (request.query().isBlank()) return new SearchResults(request.query(), List.of());
    writer.commit();
    try (var reader = DirectoryReader.open(writer)) {
      var searcher = new IndexSearcher(reader);
      var parser = new MultiFieldQueryParser(new String[] {"title", "heading", "text", "url_text"}, analyzer);
      Query text = parser.parse(MultiFieldQueryParser.escape(request.query()));
      var query = new BooleanQuery.Builder().add(text, BooleanClause.Occur.MUST);
      if (request.kind() != null)
        query.add(new TermQuery(new Term("kind", request.kind())), BooleanClause.Occur.FILTER);
      if (request.runId() != null)
        query.add(new TermQuery(new Term("run_id", request.runId().toString())), BooleanClause.Occur.FILTER);
      TopDocs found = searcher.search(query.build(), request.limit());
      List<SearchHit> hits = new java.util.ArrayList<>();
      var stored = searcher.storedFields();
      for (ScoreDoc score : found.scoreDocs) {
        var doc = stored.document(score.doc);
        hits.add(hit(doc, score.score, request.query()));
      }
      return new SearchResults(request.query(), hits);
    } catch (org.apache.lucene.queryparser.classic.ParseException e) {
      throw new IOException("Invalid search query", e);
    }
  }

  @Override
  public synchronized void commit() throws IOException {
    initialize();
    writer.commit();
  }

  @Override
  public synchronized IndexHealth health() {
    try {
      initialize();
      return new IndexHealth(
          "lucene", true, writer.getDocStats().numDocs, "Index at " + root.resolve("v1"));
    } catch (IOException e) {
      return new IndexHealth("lucene", false, 0, e.getMessage());
    }
  }

  private static SearchHit hit(org.apache.lucene.document.Document doc, float score, String query) {
    String kind = doc.get("kind");
    UUID documentId = UUID.fromString(doc.get("parent_id"));
    UUID id = UUID.fromString("chunk".equals(kind) ? doc.get("id") : doc.get("parent_id"));
    String text = "chunk".equals(kind) ? doc.get("text") : doc.get("body");
    Integer ordinal = "chunk".equals(kind) ? doc.getField("ordinal_stored").numericValue().intValue() : null;
    return new SearchHit(
        id,
        documentId,
        UUID.fromString(doc.get("run_id")),
        kind,
        doc.get("title"),
        doc.get("url"),
        ordinal,
        snippet(text == null ? "" : text, query),
        score);
  }

  private static String snippet(String text, String query) {
    if (text.length() <= 240) return text;
    String lower = text.toLowerCase(java.util.Locale.ROOT);
    int match = -1;
    for (String term : query.toLowerCase(java.util.Locale.ROOT).split("\\s+")) {
      if (term.isBlank()) continue;
      match = lower.indexOf(term);
      if (match >= 0) break;
    }
    int start = match < 0 ? 0 : Math.max(0, match - 100);
    int end = Math.min(text.length(), start + 240);
    return (start > 0 ? "..." : "") + text.substring(start, end) + (end < text.length() ? "..." : "");
  }

  @PreDestroy
  @Override
  public synchronized void close() throws IOException {
    if (writer != null) {
      writer.commit();
      writer.close();
      writer = null;
    }
  }
}
