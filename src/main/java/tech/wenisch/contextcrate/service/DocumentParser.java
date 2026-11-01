package tech.wenisch.contextcrate.service;

import static tech.wenisch.contextcrate.domain.PipelineTypes.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;
import org.jsoup.Jsoup;
import org.jsoup.nodes.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tech.wenisch.contextcrate.crawl.UrlPolicy;
import tech.wenisch.contextcrate.config.RuntimeProviderSettings;
import tech.wenisch.contextcrate.domain.*;
import tech.wenisch.contextcrate.queue.*;
import tech.wenisch.contextcrate.service.chunking.*;
import tech.wenisch.contextcrate.repository.*;
import tech.wenisch.contextcrate.storage.*;

@Service
public class DocumentParser {
  private final AcquisitionRecordRepository acquisitions;
  private final IngestionRunRepository runs;
  private final NormalizedDocumentRepository documents;
  private final DocumentChunkRepository chunks;
  private final SourceItemRepository items;
  private final ArtifactStore artifacts;
  private final IngestionService ingestion;
  private final UrlPolicy urls;
  private final PipelineQueue queue;
  private final ExtractionService extraction;
  private final ObjectMapper mapper;
  private final RuntimeProviderSettings providers;
  private tech.wenisch.contextcrate.metrics.CrateMetrics cm = tech.wenisch.contextcrate.metrics.CrateMetrics.noop();
  @org.springframework.beans.factory.annotation.Autowired(required = false)
  public void setMetrics(tech.wenisch.contextcrate.metrics.CrateMetrics metrics) { this.cm = metrics; }

  public DocumentParser(AcquisitionRecordRepository acquisitions, IngestionRunRepository runs,
      NormalizedDocumentRepository documents, DocumentChunkRepository chunks,
      SourceItemRepository items, ArtifactStore artifacts, IngestionService ingestion,
      UrlPolicy urls, PipelineQueue queue, ExtractionService extraction, ObjectMapper mapper,
      RuntimeProviderSettings providers) {
    this.acquisitions = acquisitions;
    this.runs = runs;
    this.documents = documents;
    this.chunks = chunks;
    this.items = items;
    this.artifacts = artifacts;
    this.ingestion = ingestion;
    this.urls = urls;
    this.queue = queue;
    this.extraction = extraction;
    this.mapper = mapper;
    this.providers = providers;
  }

  @Transactional
  public void parse(PipelinePayload payload) throws Exception {
    AcquisitionRecord acquisition = acquisitions.findById(payload.entityId()).orElseThrow();
    IngestionRun run = runs.findById(payload.runId()).orElseThrow();
    if (payload.crateId() != null && (!payload.crateId().equals(acquisition.getCrateId())
        || !payload.crateId().equals(run.getCrateId())))
      throw new IllegalArgumentException("Pipeline message crosses crate boundary");
    if (acquisition.getArtifactKey() == null) return;

    byte[] raw;
    try (var input = artifacts.open(acquisition.getArtifactKey())) {
      raw = input.readAllBytes();
    }
    ConnectorType connector = ingestion.connector(run);
    if (connector == ConnectorType.GIT) {
      parseText(run, acquisition, raw);
    } else {
      parseHtml(run, acquisition, raw);
    }
  }

  private void parseHtml(IngestionRun run, AcquisitionRecord acquisition, byte[] raw)
      throws Exception {
    CrawlConfiguration config = ingestion.effectiveWeb(run);
    String html = new String(raw, Charset.forName(
        acquisition.getCharset() == null ? "UTF-8" : acquisition.getCharset()));
    org.jsoup.nodes.Document page = Jsoup.parse(html, acquisition.getFinalLocator());
    int scriptCount = page.select("script").size();
    for (String selector : config.output().removeSelectors()) page.select(selector).remove();
    Element root = config.output().contentSelector().isBlank()
        ? preferredRoot(page) : page.selectFirst(config.output().contentSelector());
    if (root == null) root = page.body();
    HtmlBlocks.Result structure = root == null
        ? new HtmlBlocks.Result(List.of(), List.of()) : HtmlBlocks.parse(root);
    String text = BlockPacker.body(structure.blocks());
    if (text.length() < 100 && scriptCount > 3
        && config.reliability().renderMode() == CrawlConfiguration.RenderMode.AUTO
        && !browserRendered(acquisition)) {
      queue.publish(PipelineMessage.create(run.getCrateId(), WorkStage.BROWSER_FETCH,
          IngestionService.payload(run.getCrateId(), run.getId(), acquisition.getSourceItemId()),
          run.getId(), run.getCrateId() + ":browser:" + acquisition.getSourceItemId(), 80));
      return;
    }
    String sourceUri = canonical(page, acquisition);
    String metadata = mapper.writeValueAsString(Map.of(
        "openGraph", extractOpenGraph(page),
        "headings", page.select("h1,h2,h3").eachText(),
        "acquisitionStatus", acquisition.getStatusCode()));
    persist(run, acquisition, sourceUri, blankToNull(page.title()),
        blankToNull(page.selectFirst("html") == null ? null : page.selectFirst("html").attr("lang")),
        meta(page, "meta[name=description]", "content"),
        meta(page, "meta[name=author]", "content"), text, metadata,
        BlockPacker.pack(structure.blocks(), options(run, config.output())), config.output());
    discover(run, acquisition, page, config);
  }

  private void parseText(IngestionRun run, AcquisitionRecord acquisition, byte[] raw)
      throws Exception {
    IngestionConfiguration.Git config = ingestion.jobConfiguration(run).git();
    String path = acquisition.getRequestedLocator();
    String value = new String(raw, StandardCharsets.UTF_8);
    value = java.text.Normalizer.normalize(value.replace("\r\n", "\n").replace('\r', '\n'),
        java.text.Normalizer.Form.NFC);
    String lowerType = acquisition.getContentType() == null ? ""
        : acquisition.getContentType().toLowerCase(Locale.ROOT);
    String lowerPath = path == null ? "" : path.toLowerCase(Locale.ROOT);
    boolean markdown = lowerType.contains("markdown") || lowerPath.endsWith(".md")
        || lowerPath.endsWith(".markdown");
    String codeLanguage = markdown ? null : LanguageRegistry.languageOf(path);
    ChunkOptions options = options(run, config.output());

    String format;
    String body;
    String title = null;
    List<String> headings = List.of();
    List<ChunkDraft> drafts;
    if (markdown) {
      format = "markdown";
      var structure = MarkdownBlocks.parse(value);
      headings = structure.headings();
      body = BlockPacker.body(structure.blocks());
      title = headings.isEmpty() ? null : headings.get(0);
      drafts = BlockPacker.pack(structure.blocks(), options);
    } else if (codeLanguage != null) {
      format = "code";
      body = value.stripTrailing();
      drafts = CodeChunker.chunk(path, body, options);
    } else {
      format = "text";
      List<Block> blocks = new ArrayList<>();
      for (String paragraph : normalizeBlock(value).split("\n{2,}"))
        if (!paragraph.isBlank()) blocks.add(Block.prose(List.of(), paragraph.strip()));
      body = BlockPacker.body(blocks);
      drafts = BlockPacker.pack(blocks, options);
    }
    if (body.isBlank()) return;
    if (title == null) title = filename(path);
    Map<String, Object> meta = new LinkedHashMap<>();
    meta.put("connector", "GIT");
    meta.put("requestedRef", config.ref());
    meta.put("resolvedRevision", Objects.toString(run.getResolvedRevision(), ""));
    meta.put("path", path);
    meta.put("format", format);
    if (codeLanguage != null) meta.put("language", codeLanguage);
    meta.put("headings", headings);
    persist(run, acquisition, acquisition.getFinalLocator(), title, null, null, null, body,
        mapper.writeValueAsString(meta), drafts, config.output());
  }

  private ChunkOptions options(IngestionRun run, CrawlConfiguration.Output output) {
    int size = effectiveChunkSize(run, output.chunkSize());
    return new ChunkOptions(size, Math.min(output.chunkOverlap(), Math.max(0, size - 1)));
  }

  private void persist(IngestionRun run, AcquisitionRecord acquisition, String sourceUri,
      String title, String language, String description, String author, String body,
      String metadata, List<ChunkDraft> drafts, CrawlConfiguration.Output output) {
    String hash = Hashing.sha256(body);
    String identityUri = ingestion.connector(run) == ConnectorType.GIT
        ? acquisition.getRequestedLocator() : sourceUri;
    var current = documents.findTopByCrateIdAndSourceIdAndIdentityUriOrderByVersionNumberDesc(
        run.getCrateId(), run.getSourceId(), identityUri);
    if (current.filter(document -> document.getContentHash().equals(hash)).isPresent()) {
      cm.count("ingestion.documents", run.getCrateId(), "result", "unchanged");
      return;
    }
    UUID documentId = stable("document:" + run.getId() + ":" + sourceUri);
    NormalizedDocument value = new NormalizedDocument(documentId, run.getId(),
        acquisition.getId(), sourceUri, title, language, description, author, body,
        hash, metadata);
    value.assignCrate(run.getCrateId());
    value.version(run.getSourceId(), identityUri,
        current.map(document -> document.getVersionNumber() + 1).orElse(1));
    current.ifPresent(document -> { document.supersede(); documents.save(document); });
    NormalizedDocument document = documents.save(value);
    var produced = toChunks(document, drafts);
    chunks.saveAll(produced);
    cm.count("ingestion.documents", run.getCrateId(), "result", current.isPresent() ? "updated" : "new");
    cm.summary("ingestion.chunks_per_document", run.getCrateId(), produced.size());
    cm.summary("ingestion.document_characters", run.getCrateId(), body.length());
    extraction.publish(document, false);
    queue.publish(PipelineMessage.create(run.getCrateId(), WorkStage.INDEX,
        IngestionService.payload(run.getCrateId(), run.getId(), document.getId()), run.getId(),
        run.getCrateId() + ":index:" + document.getId(), 20));
  }

  private void discover(IngestionRun run, AcquisitionRecord acquisition,
      org.jsoup.nodes.Document page, CrawlConfiguration config) {
    int depth = items.findById(acquisition.getSourceItemId()).map(SourceItem::getDepth).orElse(0);
    if (depth >= config.scope().maxDepth()) return;
    for (Element link : page.select("a[href]")) {
      if (items.countByRunId(run.getId()) >= config.scope().maxPages()) break;
      String raw = link.absUrl("href");
      if (raw.isBlank() || !urls.inScope(raw, config.scope())) continue;
      String canonical;
      try {
        canonical = urls.canonicalize(raw);
      } catch (IllegalArgumentException e) {
        continue;
      }
      if (items.findByRunIdAndSourceUri(run.getId(), canonical).isPresent()) continue;
      try {
        SourceItem item = new SourceItem(UUID.randomUUID(), run.getId(), raw, canonical, depth + 1);
        item.assignCrate(run.getCrateId());
        item.status(FrontierStatus.QUEUED);
        items.save(item);
        queue.publish(PipelineMessage.create(run.getCrateId(), WorkStage.WEB_FETCH,
            IngestionService.payload(run.getCrateId(), run.getId(), item.getId()), run.getId(),
            run.getCrateId() + ":web-fetch:" + item.getId(), 10));
      } catch (DataIntegrityViolationException ignored) {
      }
    }
  }

  private static List<DocumentChunk> toChunks(NormalizedDocument document, List<ChunkDraft> drafts) {
    List<DocumentChunk> result = new ArrayList<>();
    int ordinal = 0;
    for (ChunkDraft draft : drafts) {
      String content = draft.content().strip();
      if (content.isBlank()) continue;
      // Code keeps its indentation, so only blank lines around it are removed.
      if (ChunkDraft.CODE.equals(draft.kind())) content = draft.content().stripTrailing();
      DocumentChunk chunk = new DocumentChunk(
          stable("chunk:" + document.getId() + ":" + ordinal), document.getId(), ordinal++,
          draft.heading(), content, Hashing.sha256(content));
      chunk.assignCrate(document.getCrateId());
      chunk.describe(draft.kind(), draft.language(), draft.symbol(), draft.startLine(),
          draft.endLine());
      result.add(chunk);
    }
    return result;
  }
  private int effectiveChunkSize(IngestionRun run, int configuredSize) {
    var embedding = providers.effectiveEmbedding(run.getCrateId());
    if (!"openai-compatible".equals(embedding.provider())) return configuredSize;
    return Math.min(configuredSize, embedding.openaiMaxInputCharacters());
  }

  private static Element preferredRoot(org.jsoup.nodes.Document page) {
    Element value = page.selectFirst("main,article,[role=main]");
    return value == null ? page.body() : value;
  }
  private static String canonical(org.jsoup.nodes.Document page, AcquisitionRecord acquisition) {
    Element link = page.selectFirst("link[rel=canonical][href]");
    String raw = link == null ? acquisition.getFinalLocator() : link.absUrl("href");
    return raw == null || raw.isBlank() ? acquisition.getRequestedLocator() : raw;
  }
  private static boolean browserRendered(AcquisitionRecord acquisition) {
    return acquisition.getArtifactKey() != null
        && acquisition.getArtifactKey().endsWith(".rendered.html");
  }
  private static String normalizeInline(String value) {
    return java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFC)
        .replaceAll("\\s+", " ").trim();
  }
  private static String normalizeBlock(String value) {
    return Arrays.stream(value.split("\n", -1))
        .map(String::stripTrailing)
        .reduce((left, right) -> left + "\n" + right).orElse("")
        .replaceAll("\n{3,}", "\n\n").trim();
  }
  private static String meta(org.jsoup.nodes.Document page, String selector, String attribute) {
    Element element = page.selectFirst(selector);
    return blankToNull(element == null ? null : element.attr(attribute));
  }
  private static Map<String, String> extractOpenGraph(org.jsoup.nodes.Document page) {
    Map<String, String> result = new LinkedHashMap<>();
    for (Element element : page.select("meta[property^=og:]"))
      result.put(element.attr("property"), element.attr("content"));
    return result;
  }
  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }
  private static String filename(String path) {
    int slash = path == null ? -1 : path.lastIndexOf('/');
    return path == null ? "Document" : path.substring(slash + 1);
  }
  private static UUID stable(String value) {
    return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8));
  }
}
