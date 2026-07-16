package tech.wenisch.harvex.service;

import static tech.wenisch.harvex.domain.PipelineTypes.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.Charset;
import java.util.*;
import org.jsoup.Jsoup;
import org.jsoup.nodes.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tech.wenisch.harvex.crawl.UrlPolicy;
import tech.wenisch.harvex.domain.*;
import tech.wenisch.harvex.queue.*;
import tech.wenisch.harvex.repository.*;
import tech.wenisch.harvex.storage.*;

@Service
public class DocumentParser {
  private final FetchRecordRepository fetches;
  private final CrawlRunRepository runs;
  private final NormalizedDocumentRepository documents;
  private final DocumentChunkRepository chunks;
  private final FrontierEntryRepository frontier;
  private final ArtifactStore artifacts;
  private final ConfigurationCodec codec;
  private final UrlPolicy urls;
  private final PipelineQueue queue;
  private final ObjectMapper mapper;

  public DocumentParser(
      FetchRecordRepository fetches,
      CrawlRunRepository runs,
      NormalizedDocumentRepository documents,
      DocumentChunkRepository chunks,
      FrontierEntryRepository frontier,
      ArtifactStore artifacts,
      ConfigurationCodec codec,
      UrlPolicy urls,
      PipelineQueue queue,
      ObjectMapper mapper) {
    this.fetches = fetches;
    this.runs = runs;
    this.documents = documents;
    this.chunks = chunks;
    this.frontier = frontier;
    this.artifacts = artifacts;
    this.codec = codec;
    this.urls = urls;
    this.queue = queue;
    this.mapper = mapper;
  }

  @Transactional
  public void parse(PipelinePayload payload) throws Exception {
    FetchRecord fetch = fetches.findById(payload.entityId()).orElseThrow();
    CrawlRun run = runs.findById(payload.runId()).orElseThrow();
    CrawlConfiguration config = codec.read(run.getConfigurationJson());
    if (fetch.getArtifactKey() == null) return;
    String html;
    try (var input = artifacts.open(fetch.getArtifactKey())) {
      html =
          new String(
              input.readAllBytes(),
              Charset.forName(fetch.getCharset() == null ? "UTF-8" : fetch.getCharset()));
    }
    org.jsoup.nodes.Document page = Jsoup.parse(html, fetch.getFinalUrl());
    int scriptCount = page.select("script").size();
    for (String selector : config.output().removeSelectors()) page.select(selector).remove();
    Element root =
        config.output().contentSelector().isBlank()
            ? preferredRoot(page)
            : page.selectFirst(config.output().contentSelector());
    if (root == null) root = page.body();
    String text = normalize(root == null ? "" : root.text());
    if (text.length() < 100
        && scriptCount > 3
        && config.reliability().renderMode() == CrawlConfiguration.RenderMode.AUTO) {
      UUID frontierId = fetch.getFrontierEntryId();
      queue.publish(
          PipelineMessage.create(
              WorkStage.BROWSER_FETCH,
              JobService.payload(run.getId(), frontierId),
              run.getId(),
              "browser:" + frontierId,
              80));
      return;
    }
    String canonical = canonical(page, fetch);
    if (documents.findByRunIdAndCanonicalUrl(run.getId(), canonical).isPresent()) return;
    String title = blankToNull(page.title());
    String language =
        blankToNull(
            page.selectFirst("html") == null ? null : page.selectFirst("html").attr("lang"));
    String description = meta(page, "meta[name=description]", "content");
    String author = meta(page, "meta[name=author]", "content");
    String metadata =
        mapper.writeValueAsString(
            Map.of(
                "openGraph",
                extractOpenGraph(page),
                "headings",
                page.select("h1,h2,h3").eachText(),
                "fetchStatus",
                fetch.getStatusCode()));
    UUID docId = stable("document:" + run.getId() + ":" + canonical);
    var document =
        documents.save(
            new NormalizedDocument(
                docId,
                run.getId(),
                fetch.getId(),
                canonical,
                title,
                language,
                description,
                author,
                text,
                Hashing.sha256(text),
                metadata));
    List<DocumentChunk> created =
        chunk(document, text, config.output().chunkSize(), config.output().chunkOverlap());
    chunks.saveAll(created);
    discover(run, fetch, page, config);
    queue.publish(
        PipelineMessage.create(
            WorkStage.INDEX,
            JobService.payload(run.getId(), document.getId()),
            run.getId(),
            "index:" + document.getId(),
            20));
  }

  private void discover(
      CrawlRun run, FetchRecord fetch, org.jsoup.nodes.Document page, CrawlConfiguration config) {
    int depth =
        frontier.findById(fetch.getFrontierEntryId()).map(FrontierEntry::getDepth).orElse(0);
    if (depth >= config.scope().maxDepth()) return;
    for (Element link : page.select("a[href]")) {
      if (frontier.countByRunId(run.getId()) >= config.scope().maxPages()) break;
      String raw = link.absUrl("href");
      if (raw.isBlank() || !urls.inScope(raw, config.scope())) continue;
      String canonical;
      try {
        canonical = urls.canonicalize(raw);
      } catch (IllegalArgumentException e) {
        continue;
      }
      if (frontier.findByRunIdAndCanonicalUrl(run.getId(), canonical).isPresent()) continue;
      try {
        var entry =
            frontier.save(
                new FrontierEntry(UUID.randomUUID(), run.getId(), raw, canonical, depth + 1));
        entry.status(FrontierStatus.QUEUED);
        frontier.save(entry);
        queue.publish(
            PipelineMessage.create(
                WorkStage.FETCH,
                JobService.payload(run.getId(), entry.getId()),
                run.getId(),
                "fetch:" + entry.getId(),
                10));
      } catch (DataIntegrityViolationException ignored) {
      }
    }
  }

  private static List<DocumentChunk> chunk(
      NormalizedDocument doc, String text, int size, int overlap) {
    List<DocumentChunk> result = new ArrayList<>();
    int start = 0, ordinal = 0;
    while (start < text.length()) {
      int end = Math.min(text.length(), start + size);
      if (end < text.length()) {
        int boundary = text.lastIndexOf(' ', end);
        if (boundary > start + size / 2) end = boundary;
      }
      String value = text.substring(start, end).trim();
      if (!value.isBlank())
        result.add(
            new DocumentChunk(
                stable("chunk:" + doc.getId() + ":" + ordinal),
                doc.getId(),
                ordinal++,
                null,
                value,
                Hashing.sha256(value)));
      if (end >= text.length()) break;
      start = Math.max(start + 1, end - overlap);
    }
    return result;
  }

  private static Element preferredRoot(org.jsoup.nodes.Document d) {
    Element e = d.selectFirst("main,article,[role=main]");
    return e == null ? d.body() : e;
  }

  private static String canonical(org.jsoup.nodes.Document page, FetchRecord fetch) {
    Element link = page.selectFirst("link[rel=canonical][href]");
    String raw = link == null ? fetch.getFinalUrl() : link.absUrl("href");
    return raw == null || raw.isBlank() ? fetch.getRequestedUrl() : raw;
  }

  private static String normalize(String text) {
    return java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFC)
        .replaceAll("\\s+", " ")
        .trim();
  }

  private static String meta(org.jsoup.nodes.Document d, String selector, String attr) {
    Element e = d.selectFirst(selector);
    return blankToNull(e == null ? null : e.attr(attr));
  }

  private static Map<String, String> extractOpenGraph(org.jsoup.nodes.Document d) {
    Map<String, String> values = new LinkedHashMap<>();
    for (Element e : d.select("meta[property^=og:]"))
      values.put(e.attr("property"), e.attr("content"));
    return values;
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }

  private static UUID stable(String value) {
    return UUID.nameUUIDFromBytes(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }
}
