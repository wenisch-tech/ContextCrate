package tech.wenisch.contextcrate.web;

import static tech.wenisch.contextcrate.domain.PipelineTypes.WorkStage;

import java.util.*;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import tech.wenisch.contextcrate.config.ContextCrateProperties;
import tech.wenisch.contextcrate.domain.CrawlConfiguration;
import tech.wenisch.contextcrate.domain.PipelineTypes.ExtractionType;
import tech.wenisch.contextcrate.index.SearchIndex;
import tech.wenisch.contextcrate.queue.PipelineQueue;
import tech.wenisch.contextcrate.repository.*;
import tech.wenisch.contextcrate.service.*;
import tech.wenisch.contextcrate.answer.RagSettingsService;
import tech.wenisch.contextcrate.config.RuntimeProviderSettings;
import tech.wenisch.contextcrate.embedding.LocalOnnxEmbeddingProvider;
import tech.wenisch.contextcrate.reranking.ConfigurableRerankingProvider;
import tech.wenisch.contextcrate.domain.*;

@Controller
@RequestMapping("/crates/{crateId}")
public class UiController {
  private final SourceService sources;
  private final IngestionService ingestion;
  private final SourceConfigurationCodec sourceCodec;
  private final IngestionConfigurationCodec ingestionCodec;
  private final ContextCrateProperties properties;
  private final PipelineQueue queue;
  private final SearchIndex index;
  private final NormalizedDocumentRepository documents;
  private final DocumentChunkRepository documentChunks;
  private final ExtractionService extraction;
  private final RagSettingsService ragSettings;
  private final RuntimeProviderSettings providerSettings;
  private final LocalOnnxEmbeddingProvider localEmbeddings;
  private final ConfigurableRerankingProvider reranking;
  private final AcquisitionRecordRepository acquisitionRecords;
  private final SourceItemRepository sourceItems;
  private final CrateService crates;
  private final CrateAccessService access;
  private final IndexRebuildService rebuild;
  private final DocumentIndexRecoveryService indexRecovery;
  private final PipelineWorkItemRepository pipelineWork;
  private final DocumentDiffService documentDiffs;

  public UiController(
      SourceService sources,
      IngestionService ingestion,
      SourceConfigurationCodec sourceCodec,
      IngestionConfigurationCodec ingestionCodec,
      ContextCrateProperties properties,
      PipelineQueue queue,
      SearchIndex index,
      NormalizedDocumentRepository documents,
      DocumentChunkRepository documentChunks,
      ExtractionService extraction,
      RagSettingsService ragSettings,
      RuntimeProviderSettings providerSettings,
      LocalOnnxEmbeddingProvider localEmbeddings,
      ConfigurableRerankingProvider reranking,
      AcquisitionRecordRepository acquisitionRecords,
      SourceItemRepository sourceItems, CrateService crates, CrateAccessService access,
      IndexRebuildService rebuild, DocumentIndexRecoveryService indexRecovery,
      PipelineWorkItemRepository pipelineWork, DocumentDiffService documentDiffs) {
    this.sources = sources;
    this.ingestion = ingestion;
    this.sourceCodec = sourceCodec;
    this.ingestionCodec = ingestionCodec;
    this.properties = properties;
    this.queue = queue;
    this.index = index;
    this.documents = documents;
    this.documentChunks = documentChunks;
    this.extraction = extraction;
    this.ragSettings = ragSettings;
    this.providerSettings = providerSettings;
    this.localEmbeddings = localEmbeddings;
    this.reranking = reranking;
    this.acquisitionRecords = acquisitionRecords;
    this.sourceItems = sourceItems;
    this.crates = crates;
    this.access = access;
    this.rebuild = rebuild;
    this.indexRecovery = indexRecovery;
    this.pipelineWork = pipelineWork;
    this.documentDiffs = documentDiffs;
  }

  @ModelAttribute
  void crate(@PathVariable UUID crateId, Model model) {
    model.addAttribute("crate", crates.require(crateId, CrateMember.Role.VIEWER));
    model.addAttribute("crates", crates.accessible());
    model.addAttribute("adminElevation", access.activeElevation(crateId).orElse(null));
  }

  @GetMapping({"", "/"})
  String dashboard(@PathVariable UUID crateId,@RequestParam(defaultValue = "") String q, Model model) throws Exception {
    var allJobs = ingestion.allJobs(crateId);
    model.addAttribute("sources", sources.list(crateId));
    model.addAttribute("jobs", allJobs);
    model.addAttribute("runs", ingestion.runs(crateId));
    model.addAttribute(
        "jobNames",
        allJobs.stream()
            .collect(java.util.stream.Collectors.toMap(IngestionJob::getId, IngestionJob::getName)));
    model.addAttribute("properties", properties);
    model.addAttribute("indexHealth", index.health(crateId));
    model.addAttribute(
        "queueDepth",
        Arrays.stream(WorkStage.values())
            .collect(java.util.stream.Collectors.toMap(Enum::name, queue::depth)));
    model.addAttribute("documentCount", documents.findByCrateIdAndCurrentVersionTrue(crateId).size());
        model.addAttribute("searchQuery", q);
        model.addAttribute(
          "searchResults",
          q.isBlank() ? null : index.search(new SearchIndex.SearchRequest(crateId,q, 10, null, null, null)));
    return "dashboard";
  }

  @GetMapping("/sources")
  String sourceList(@PathVariable UUID crateId, Model model) {
    model.addAttribute("sources", sources.list(crateId));
    model.addAttribute("sourceService", sources);
    model.addAttribute("sourceCodec", sourceCodec);
    return "sources";
  }

  @GetMapping("/sources/new")
  String newSource(Model model) {
    model.addAttribute("connectorTypes", ConnectorType.values());
    return "source-form";
  }

  @PostMapping("/sources")
  String createSource(@PathVariable UUID crateId, @RequestParam String name,
      @RequestParam(defaultValue = "") String description,
      @RequestParam ConnectorType connectorType, @RequestParam String endpoint) {
    access.requireMutable(crateId,CrateMember.Role.EDITOR);
    SourceConfiguration config = connectorType == ConnectorType.GIT
        ? SourceConfiguration.git(endpoint)
        : SourceConfiguration.https(endpoint);
    Source source = sources.create(crateId, name, description, connectorType, config);
    return "redirect:/crates/" + crateId + "/sources/" + source.getId();
  }

  @GetMapping("/sources/{sourceId}")
  String sourceDetails(@PathVariable UUID crateId, @PathVariable UUID sourceId, Model model) {
    Source source = sources.require(crateId, sourceId);
    model.addAttribute("source", source);
    model.addAttribute("sourceConfig", sourceCodec.read(source.getConfigurationJson(),
        source.getConnectorType()).withoutSecrets());
    model.addAttribute("jobs", ingestion.jobs(crateId, sourceId));
    model.addAttribute("ingestionCodec", ingestionCodec);
    return "source-details";
  }

  @GetMapping("/sources/{sourceId}/edit")
  String editSource(@PathVariable UUID crateId, @PathVariable UUID sourceId, Model model) {
    Source source = sources.require(crateId, sourceId);
    model.addAttribute("source", source);
    model.addAttribute("config", sourceCodec.read(source.getConfigurationJson(),
        source.getConnectorType()).withoutSecrets());
    model.addAttribute("connectorTypes", ConnectorType.values());
    return "source-form";
  }

  @PostMapping("/sources/{sourceId}/update")
  String updateSource(@PathVariable UUID crateId, @PathVariable UUID sourceId,
      @RequestParam String name, @RequestParam(defaultValue = "") String description,
      @RequestParam String endpoint) {
    access.requireMutable(crateId, CrateMember.Role.EDITOR);
    Source source = sources.require(crateId, sourceId);
    SourceConfiguration config = source.getConnectorType() == ConnectorType.GIT
        ? SourceConfiguration.git(endpoint)
        : SourceConfiguration.https(endpoint);
    sources.update(crateId, sourceId, name, description, config, source.isEnabled());
    return "redirect:/crates/" + crateId + "/sources/" + sourceId;
  }

  @GetMapping("/sources/{sourceId}/ingestion-jobs/new")
  String newIngestionJob(@PathVariable UUID crateId, @PathVariable UUID sourceId, Model model) {
    Source source = sources.require(crateId, sourceId);
    model.addAttribute("source", source);
    model.addAttribute("sourceConfig", sourceCodec.read(source.getConfigurationJson(),
        source.getConnectorType()));
    return "ingestion-job-form";
  }

  @GetMapping("/sources/{sourceId}/ingestion-jobs/{jobId}/edit")
  String editIngestionJob(@PathVariable UUID crateId, @PathVariable UUID sourceId,
      @PathVariable UUID jobId, Model model) {
    Source source = sources.require(crateId, sourceId);
    IngestionJob job = ingestion.requireJob(crateId, sourceId, jobId);
    model.addAttribute("source", source);
    model.addAttribute("sourceConfig", sourceCodec.read(source.getConfigurationJson(),
        source.getConnectorType()));
    model.addAttribute("job", job);
    model.addAttribute("config", ingestionCodec.read(job.getConfigurationJson(),
        source.getConnectorType()));
    return "ingestion-job-form";
  }

  @PostMapping("/sources/{sourceId}/ingestion-jobs")
  String createIngestionJob(@PathVariable UUID crateId, @PathVariable UUID sourceId,
      @RequestParam String name, @RequestParam(defaultValue = "") String seedUrl,
      @RequestParam(defaultValue = "") String allowedHost,
      @RequestParam(defaultValue = "") String ref,
      @RequestParam(defaultValue = "") String gitUsername,
      @RequestParam(defaultValue = "") String gitToken,
      @RequestParam(defaultValue = "**") String includePatterns,
      @RequestParam(defaultValue = "") String excludePatterns,
      @RequestParam(defaultValue = "3") int maxDepth,
      @RequestParam(defaultValue = "1000") int maxPages,
      @RequestParam(defaultValue = "10000") int maxFiles,
      @RequestParam(defaultValue = "1048576") long maxFileBytes,
      @RequestParam(defaultValue = "1000") int chunkSize,
      @RequestParam(defaultValue = "200") int chunkOverlap,
      @RequestParam(defaultValue = "") String includeUrlPatterns,
      @RequestParam(defaultValue = "") String excludeUrlPatterns,
      @RequestParam(defaultValue = "false") boolean allowSubdomains,
      @RequestParam(defaultValue = "true") boolean discoverSitemaps,
      @RequestParam(defaultValue = "ContextCrateBot/0.1") String userAgent,
      @RequestParam(defaultValue = "") String contact,
      @RequestParam(defaultValue = "true") boolean honorRobots,
      @RequestParam(defaultValue = "1") int perHostConcurrency,
      @RequestParam(defaultValue = "1000") long minimumDelayMillis,
      @RequestParam(defaultValue = "15000") int timeoutMillis,
      @RequestParam(defaultValue = "3") int maxAttempts,
      @RequestParam(defaultValue = "1000") long initialBackoffMillis,
      @RequestParam(defaultValue = "10000000") long maxBodyBytes,
      @RequestParam(defaultValue = "true") boolean deduplicateContent,
      @RequestParam(defaultValue = "AUTO") CrawlConfiguration.RenderMode renderMode,
      @RequestParam(defaultValue = "30") int rawRetentionDays,
      @RequestParam(defaultValue = "") String contentSelector,
      @RequestParam(defaultValue = "script,style,nav,footer,aside") String removeSelectors,
      @RequestParam(defaultValue = "default") String logicalIndex,
      @RequestParam(defaultValue = "NONE") CrawlConfiguration.AuthMethod authMethod,
      @RequestParam(defaultValue = "") String loginPageUrl,
      @RequestParam(defaultValue = "") String username,
      @RequestParam(defaultValue = "") String password,
      @RequestParam(defaultValue = "username") String usernameField,
      @RequestParam(defaultValue = "password") String passwordField,
      @RequestParam(defaultValue = "button[type='submit'], input[type='submit']") String submitSelector,
      @RequestParam(defaultValue = "") String successUrlPattern,
      @RequestParam(defaultValue = "") String successContentPattern,
      @RequestParam(defaultValue = "false") boolean directLogin,
      @RequestParam(defaultValue = "") String authServerUrl,
      @RequestParam(defaultValue = "") String clientId,
      @RequestParam(defaultValue = "") String clientSecret,
      @RequestParam(defaultValue = "") String realm) {
    access.requireMutable(crateId, CrateMember.Role.EDITOR);
    Source source = sources.require(crateId, sourceId);
    ingestion.create(crateId, sourceId, name, formConfiguration(source, seedUrl, allowedHost, ref,
        gitUsername, gitToken,
        includePatterns, excludePatterns, maxDepth, maxPages, maxFiles, maxFileBytes,
        chunkSize, chunkOverlap, includeUrlPatterns, excludeUrlPatterns, allowSubdomains,
        discoverSitemaps, userAgent, contact, honorRobots, perHostConcurrency,
        minimumDelayMillis, timeoutMillis, maxAttempts, initialBackoffMillis, maxBodyBytes,
        deduplicateContent, renderMode, rawRetentionDays, contentSelector, removeSelectors,
        logicalIndex, authMethod, loginPageUrl, username, password, usernameField, passwordField,
        submitSelector, successUrlPattern, successContentPattern, directLogin, authServerUrl,
        clientId, clientSecret, realm));
    return "redirect:/crates/" + crateId + "/sources/" + sourceId;
  }

  @PostMapping("/sources/{sourceId}/ingestion-jobs/{jobId}/update")
  String updateIngestionJob(@PathVariable UUID crateId, @PathVariable UUID sourceId,
      @PathVariable UUID jobId, @RequestParam String name,
      @RequestParam(defaultValue = "") String seedUrl,
      @RequestParam(defaultValue = "") String allowedHost,
      @RequestParam(defaultValue = "") String ref,
      @RequestParam(defaultValue = "") String gitUsername,
      @RequestParam(defaultValue = "") String gitToken,
      @RequestParam(defaultValue = "**") String includePatterns,
      @RequestParam(defaultValue = "") String excludePatterns,
      @RequestParam(defaultValue = "3") int maxDepth,
      @RequestParam(defaultValue = "1000") int maxPages,
      @RequestParam(defaultValue = "10000") int maxFiles,
      @RequestParam(defaultValue = "1048576") long maxFileBytes,
      @RequestParam(defaultValue = "1000") int chunkSize,
      @RequestParam(defaultValue = "200") int chunkOverlap,
      @RequestParam(defaultValue = "") String includeUrlPatterns,
      @RequestParam(defaultValue = "") String excludeUrlPatterns,
      @RequestParam(defaultValue = "false") boolean allowSubdomains,
      @RequestParam(defaultValue = "true") boolean discoverSitemaps,
      @RequestParam(defaultValue = "ContextCrateBot/0.1") String userAgent,
      @RequestParam(defaultValue = "") String contact,
      @RequestParam(defaultValue = "true") boolean honorRobots,
      @RequestParam(defaultValue = "1") int perHostConcurrency,
      @RequestParam(defaultValue = "1000") long minimumDelayMillis,
      @RequestParam(defaultValue = "15000") int timeoutMillis,
      @RequestParam(defaultValue = "3") int maxAttempts,
      @RequestParam(defaultValue = "1000") long initialBackoffMillis,
      @RequestParam(defaultValue = "10000000") long maxBodyBytes,
      @RequestParam(defaultValue = "true") boolean deduplicateContent,
      @RequestParam(defaultValue = "AUTO") CrawlConfiguration.RenderMode renderMode,
      @RequestParam(defaultValue = "30") int rawRetentionDays,
      @RequestParam(defaultValue = "") String contentSelector,
      @RequestParam(defaultValue = "script,style,nav,footer,aside") String removeSelectors,
      @RequestParam(defaultValue = "default") String logicalIndex,
      @RequestParam(defaultValue = "NONE") CrawlConfiguration.AuthMethod authMethod,
      @RequestParam(defaultValue = "") String loginPageUrl,
      @RequestParam(defaultValue = "") String username,
      @RequestParam(defaultValue = "") String password,
      @RequestParam(defaultValue = "username") String usernameField,
      @RequestParam(defaultValue = "password") String passwordField,
      @RequestParam(defaultValue = "button[type='submit'], input[type='submit']") String submitSelector,
      @RequestParam(defaultValue = "") String successUrlPattern,
      @RequestParam(defaultValue = "") String successContentPattern,
      @RequestParam(defaultValue = "false") boolean directLogin,
      @RequestParam(defaultValue = "") String authServerUrl,
      @RequestParam(defaultValue = "") String clientId,
      @RequestParam(defaultValue = "") String clientSecret,
      @RequestParam(defaultValue = "") String realm) {
    access.requireMutable(crateId, CrateMember.Role.EDITOR);
    Source source = sources.require(crateId, sourceId);
    IngestionJob job = ingestion.requireJob(crateId, sourceId, jobId);
    ingestion.update(crateId, sourceId, jobId, name,
        formConfiguration(source, seedUrl, allowedHost, ref, gitUsername, gitToken,
            includePatterns, excludePatterns,
            maxDepth, maxPages, maxFiles, maxFileBytes, chunkSize, chunkOverlap,
            includeUrlPatterns, excludeUrlPatterns, allowSubdomains, discoverSitemaps, userAgent,
            contact, honorRobots, perHostConcurrency, minimumDelayMillis, timeoutMillis,
            maxAttempts, initialBackoffMillis, maxBodyBytes, deduplicateContent, renderMode,
            rawRetentionDays, contentSelector, removeSelectors, logicalIndex, authMethod,
            loginPageUrl, username, password, usernameField, passwordField, submitSelector,
            successUrlPattern, successContentPattern, directLogin, authServerUrl, clientId,
            clientSecret, realm),
        job.isEnabled());
    return "redirect:/crates/" + crateId + "/sources/" + sourceId;
  }

  @PostMapping("/sources/{sourceId}/ingestion-jobs/{jobId}/start")
  String start(@PathVariable UUID crateId, @PathVariable UUID sourceId,
      @PathVariable UUID jobId) {
    access.requireMutable(crateId, CrateMember.Role.EDITOR);
    ingestion.start(crateId, sourceId, jobId);
    return "redirect:/crates/" + crateId;
  }

  @GetMapping("/runs/{id}")
  String runDetails(@PathVariable UUID crateId,@PathVariable UUID id, Model model) throws Exception {
    var run = ingestion.requireRun(crateId,id);
    var job = ingestion.requireJob(crateId,run.getIngestionJobId());
    var source = sources.require(crateId,run.getSourceId());
    var config = ingestionCodec.read(run.getJobConfigurationJson(), source.getConnectorType());

    model.addAttribute("run", run);
    model.addAttribute("job", job);
    model.addAttribute("source", source);
    model.addAttribute("config", config);

    model.addAttribute("fetches", acquisitionRecords.findTop100ByRunIdOrderByFetchedAtDesc(id));
    model.addAttribute("frontierTotal", sourceItems.countByRunId(id));
    model.addAttribute(
        "frontierFetched",
        sourceItems.countByRunIdAndStatus(
            id, tech.wenisch.contextcrate.domain.PipelineTypes.FrontierStatus.FETCHED));
    model.addAttribute(
        "frontierFailed",
        sourceItems.countByRunIdAndStatus(
            id, tech.wenisch.contextcrate.domain.PipelineTypes.FrontierStatus.FAILED));
    model.addAttribute("pipelineWork", pipelineWork.findTop100ByCorrelationIdOrderByUpdatedAtDesc(id));

    return "run-details";
  }

  private static IngestionConfiguration formConfiguration(Source source, String seedUrl,
      String allowedHost, String ref, String gitUsername, String gitToken,
      String includePatterns, String excludePatterns,
      int maxDepth, int maxPages, int maxFiles, long maxFileBytes, int chunkSize,
      int chunkOverlap, String includeUrlPatterns, String excludeUrlPatterns,
      boolean allowSubdomains, boolean discoverSitemaps, String userAgent, String contact,
      boolean honorRobots, int perHostConcurrency, long minimumDelayMillis, int timeoutMillis,
      int maxAttempts, long initialBackoffMillis, long maxBodyBytes, boolean deduplicateContent,
      CrawlConfiguration.RenderMode renderMode, int rawRetentionDays, String contentSelector,
      String removeSelectors, String logicalIndex, CrawlConfiguration.AuthMethod authMethod,
      String loginPageUrl, String username, String password, String usernameField,
      String passwordField, String submitSelector, String successUrlPattern,
      String successContentPattern, boolean directLogin, String authServerUrl, String clientId,
      String clientSecret, String realm) {
    CrawlConfiguration.Output output = new CrawlConfiguration.Output(
        rawRetentionDays, contentSelector, csv(removeSelectors), chunkSize, chunkOverlap, logicalIndex);
    if (source.getConnectorType() == ConnectorType.GIT)
      return IngestionConfiguration.git(new IngestionConfiguration.Git(ref, blankToNull(gitUsername),
          blankToNull(gitToken), csv(includePatterns), csv(excludePatterns), maxFiles,
          maxFileBytes, output));
    CrawlConfiguration.Politeness politeness = new CrawlConfiguration.Politeness(userAgent,
        contact, honorRobots, perHostConcurrency, minimumDelayMillis, timeoutMillis);
    CrawlConfiguration.Reliability reliability = new CrawlConfiguration.Reliability(maxAttempts,
        initialBackoffMillis, maxBodyBytes, deduplicateContent, renderMode);
    CrawlConfiguration.LoginConfiguration login = new CrawlConfiguration.LoginConfiguration(
        blankToNull(loginPageUrl), blankToNull(username), blankToNull(password), usernameField,
        passwordField, submitSelector, new CrawlConfiguration.SuccessDetection(
            blankToNull(successUrlPattern), blankToNull(successContentPattern)), directLogin,
        blankToNull(authServerUrl), blankToNull(clientId), blankToNull(clientSecret),
        blankToNull(realm), authMethod);
    return IngestionConfiguration.web(new CrawlConfiguration(
        new CrawlConfiguration.Scope(seedUrl, new java.util.LinkedHashSet<>(csv(allowedHost)),
            csv(includeUrlPatterns), csv(excludeUrlPatterns), maxDepth, maxPages,
            allowSubdomains, discoverSitemaps), politeness, reliability, output, login));
  }

  private static List<String> csv(String value) {
    return value == null || value.isBlank() ? List.of()
        : Arrays.stream(value.split(",")).map(String::trim).filter(v -> !v.isBlank()).toList();
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value;
  }

  @GetMapping("/documents")
  String docs(@PathVariable UUID crateId, @RequestParam(defaultValue = "") String q,
      @RequestParam(defaultValue = "created") String sort,
      @RequestParam(defaultValue = "desc") String direction,
      @RequestParam(defaultValue = "0") int page, Model model) {
    var requestedSort = DocumentSort.from(sort);
    var requestedDirection = "asc".equalsIgnoreCase(direction)
        ? org.springframework.data.domain.Sort.Direction.ASC
        : org.springframework.data.domain.Sort.Direction.DESC;
    var values = documents.findCurrentPage(crateId, q, requestedSort, requestedDirection,
        PageRequest.of(Math.max(0, page), 50));
    model.addAttribute("documentPage", values);
    model.addAttribute("query", q);
    model.addAttribute("sort", requestedSort.name().toLowerCase(Locale.ROOT));
    model.addAttribute("direction", requestedDirection.name().toLowerCase(Locale.ROOT));
    model.addAttribute("nextDirections", java.util.Map.of(
        "title", nextDirection(requestedSort, requestedDirection, DocumentSort.TITLE),
        "uri", nextDirection(requestedSort, requestedDirection, DocumentSort.URI),
        "chunks", nextDirection(requestedSort, requestedDirection, DocumentSort.CHUNKS),
        "indexed", nextDirection(requestedSort, requestedDirection, DocumentSort.INDEXED)));
    model.addAttribute("unindexedCount", documents.findByCrateIdAndCurrentVersionTrueAndIndexedFalse(crateId).size());
    return "documents";
  }

  @GetMapping("/documents/{id}")
  String documentDetails(@PathVariable UUID crateId, @PathVariable UUID id, Model model) {
    var document = documents.findByIdAndCrateId(id, crateId).orElseThrow();
    var versions = documents.findByCrateIdAndSourceIdAndIdentityUriOrderByVersionNumberDesc(
        crateId, document.getSourceId(), document.getIdentityUri());
    var previous = new HashMap<UUID, UUID>();
    for (int position = 0; position + 1 < versions.size(); position++)
      previous.put(versions.get(position).getId(), versions.get(position + 1).getId());
    model.addAttribute("document", document);
    model.addAttribute("source", sources.require(crateId, document.getSourceId()));
    model.addAttribute("run", ingestion.requireRun(crateId, document.getRunId()));
    model.addAttribute("versions", versions);
    model.addAttribute("previousVersions", previous);
    model.addAttribute("chunks", documentChunks.findByDocumentIdAndCrateIdOrderByOrdinal(id, crateId));
    return "document-details";
  }

  @GetMapping("/documents/{id}/versions")
  String documentVersions(@PathVariable UUID crateId, @PathVariable UUID id, Model model) {
    var document = documents.findByIdAndCrateId(id, crateId).orElseThrow();
    model.addAttribute("document", document);
    model.addAttribute("versions", documents.findByCrateIdAndSourceIdAndIdentityUriOrderByVersionNumberDesc(
        crateId, document.getSourceId(), document.getIdentityUri()));
    return "document-versions";
  }

  @GetMapping("/documents/{id}/versions/{previousId}/diff")
  String documentDiff(@PathVariable UUID crateId, @PathVariable UUID id,
      @PathVariable UUID previousId, Model model) {
    var current = documents.findByIdAndCrateId(id, crateId).orElseThrow();
    var previous = documents.findByIdAndCrateId(previousId, crateId).orElseThrow();
    if (!current.getSourceId().equals(previous.getSourceId())
        || !current.getIdentityUri().equals(previous.getIdentityUri())
        || current.getVersionNumber() != previous.getVersionNumber() + 1)
      throw new org.springframework.web.server.ResponseStatusException(
          org.springframework.http.HttpStatus.NOT_FOUND);
    model.addAttribute("document", current);
    model.addAttribute("previous", previous);
    model.addAttribute("diff", documentDiffs.unified(previous, current));
    return "document-diff";
  }

  @PostMapping("/documents/retry-unindexed")
  String retryUnindexedDocuments(@PathVariable UUID crateId,
      @RequestParam(defaultValue = "") String q, @RequestParam(defaultValue = "created") String sort,
      @RequestParam(defaultValue = "desc") String direction,
      @RequestParam(defaultValue = "0") int page, RedirectAttributes redirect) {
    access.requireMutable(crateId, CrateMember.Role.EDITOR);
    int queued = indexRecovery.enqueueMissing(crateId);
    redirect.addFlashAttribute("indexRecoveryMessage", queued == 0
        ? "No unindexed documents were found."
        : "Queued " + queued + " document" + (queued == 1 ? "" : "s") + " for indexing recovery.");
    redirect.addAttribute("q", q);
    redirect.addAttribute("sort", sort);
    redirect.addAttribute("direction", direction);
    redirect.addAttribute("page", Math.max(0, page));
    return "redirect:/crates/" + crateId + "/documents";
  }

  private static String nextDirection(DocumentSort active, org.springframework.data.domain.Sort.Direction direction,
      DocumentSort target) {
    return active == target && direction.isAscending() ? "desc" : "asc";
  }

  @GetMapping("/extractions")
  String extractions(@PathVariable UUID crateId,Model model) {
    model.addAttribute("rules", extraction.rules(crateId));
    model.addAttribute("types", ExtractionType.values());
    model.addAttribute("runs", ingestion.runs(crateId));
    model.addAttribute(
        "results", extraction.search(crateId,null, null, null, null, null, PageRequest.of(0, 50)));
    model.addAttribute("extraction", extraction);
    return "extractions";
  }

  @PostMapping("/extractions/rules")
  String createExtractionRule(
      @PathVariable UUID crateId,@RequestParam String name,
      @RequestParam ExtractionType type,
      @RequestParam(defaultValue = "") String pattern,
      @RequestParam(defaultValue = "false") boolean enabled) {
    access.requireMutable(crateId,CrateMember.Role.EDITOR);
    extraction.createRule(crateId,name, type, pattern, enabled);
    return "redirect:/crates/"+crateId+"/extractions";
  }

  @PostMapping("/extractions/rules/{id}/disable")
  String disableExtractionRule(@PathVariable UUID crateId,@PathVariable UUID id) {
    access.requireMutable(crateId,CrateMember.Role.EDITOR);
    extraction.deleteRule(crateId,id);
    return "redirect:/crates/"+crateId+"/extractions";
  }

  @PostMapping("/extractions/runs/{id}/rebuild")
  String rebuildRunExtractions(@PathVariable UUID crateId,@PathVariable UUID id) {
    access.requireMutable(crateId,CrateMember.Role.EDITOR);ingestion.requireRun(crateId,id);
    extraction.rebuildRun(crateId,id);
    return "redirect:/crates/"+crateId+"/extractions";
  }

  @GetMapping("/operations")
  String operations(@PathVariable UUID crateId,Model model) {
    model.addAttribute("properties", properties);
    model.addAttribute("indexHealth", index.health(crateId));
    model.addAttribute("deadLetters", queue.deadLetters());
    model.addAttribute(
        "queueDepth",
        Arrays.stream(WorkStage.values())
            .collect(java.util.stream.Collectors.toMap(Enum::name, queue::depth)));
    return "operations";
  }

  @GetMapping("/settings")
  String settings(@PathVariable UUID crateId,Model model) {
    access.requireMutable(crateId,CrateMember.Role.OWNER);
    model.addAttribute("rag", ragSettings.current(crateId));
    model.addAttribute("answering", properties.answering());
    model.addAttribute("embeddingProvider", providerSettings.effectiveEmbedding(crateId));
    model.addAttribute("rerankingProvider", providerSettings.effectiveReranking(crateId));
    model.addAttribute("answerProvider", providerSettings.effectiveAnswer(crateId));
    return "settings";
  }

  @PostMapping("/settings/rag")
  String saveRagSettings(
      @PathVariable UUID crateId,@RequestParam(defaultValue = "false") boolean strictGrounding,
      @RequestParam(defaultValue = "false") boolean allowClientHistory,
      @RequestParam(defaultValue = "false") boolean inlineCitations,
      @RequestParam(defaultValue = "false") boolean structuredSources,
      @RequestParam String retrievalMode,
      @RequestParam int sourceLimit) {
    access.requireMutable(crateId,CrateMember.Role.OWNER);
    ragSettings.update(crateId,strictGrounding, allowClientHistory, inlineCitations, structuredSources, retrievalMode, sourceLimit);
    return "redirect:/crates/"+crateId+"/settings?saved";
  }

  @PostMapping("/settings/providers")
  String saveProviderSettings(
      @PathVariable UUID crateId,@RequestParam(defaultValue="false") boolean embeddingsEnabled, @RequestParam String embeddingProvider,
      @RequestParam String embeddingModelId, @RequestParam String embeddingRevision, @RequestParam String embeddingDownloadUrl,
      @RequestParam String embeddingCachePath, @RequestParam(required=false) String embeddingModelPath,
      @RequestParam(required=false) String embeddingBaseUrl, @RequestParam(required=false) String embeddingRemoteModel,
      @RequestParam(required=false) String embeddingApiKey, @RequestParam int embeddingDimensions,
      @RequestParam int embeddingMaxInputCharacters,
      @RequestParam(defaultValue="false") boolean embeddingAutomaticLimitRecovery,
      @RequestParam(defaultValue="false") boolean rerankingEnabled, @RequestParam String rerankingProvider,
      @RequestParam int rerankingCandidateLimit, @RequestParam(required=false) String rerankingModelId,
      @RequestParam(required=false) String rerankingRevision, @RequestParam(required=false) String rerankingDownloadUrl,
      @RequestParam(required=false) String rerankingCachePath, @RequestParam(required=false) String rerankingModelPath,
      @RequestParam(required=false) String rerankingBaseUrl, @RequestParam(required=false) String rerankingRemoteModel,
      @RequestParam(required=false) String rerankingApiKey, @RequestParam int rerankingMaxInputCharacters,
      @RequestParam int rerankingTimeoutSeconds,
      @RequestParam(defaultValue="false") boolean answeringEnabled, @RequestParam(required=false) String answeringBaseUrl,
      @RequestParam(required=false) String answeringModel, @RequestParam(required=false) String answeringApiKey) {
    access.requireMutable(crateId,CrateMember.Role.OWNER);
    var previous = providerSettings.effectiveEmbedding(crateId);
    providerSettings.update(crateId,new RuntimeProviderSettings.ProviderForm(embeddingsEnabled,embeddingProvider,embeddingModelId,embeddingRevision,embeddingDownloadUrl,embeddingCachePath,embeddingModelPath,embeddingBaseUrl,embeddingRemoteModel,embeddingApiKey,embeddingDimensions,embeddingMaxInputCharacters,embeddingAutomaticLimitRecovery,rerankingEnabled,rerankingProvider,rerankingCandidateLimit,rerankingModelId,rerankingRevision,rerankingDownloadUrl,rerankingCachePath,rerankingModelPath,rerankingBaseUrl,rerankingRemoteModel,rerankingApiKey,rerankingMaxInputCharacters,rerankingTimeoutSeconds,answeringEnabled,answeringBaseUrl,answeringModel,answeringApiKey));
    if (!previous.toString().equals(providerSettings.effectiveEmbedding(crateId).toString()))
      rebuild.rebuildAsync(crateId);
    return "redirect:/crates/"+crateId+"/settings?providersSaved";
  }

  @PostMapping("/settings/providers/local/download")
  @ResponseBody
  java.util.Map<String, String> downloadLocalModel(@PathVariable UUID crateId) throws Exception {
    access.requireMutable(crateId,CrateMember.Role.OWNER);
    if (!"local".equals(providerSettings.effectiveEmbedding(crateId).provider()))
      throw new IllegalStateException("Select and save the local embedding provider before downloading a model");
    try(var ignored=tech.wenisch.contextcrate.config.CrateContext.use(crateId)){localEmbeddings.downloadModel();}
    return java.util.Map.of("status", "Local embedding model is ready");
  }

  @PostMapping("/settings/providers/reranking/local/download")
  @ResponseBody
  java.util.Map<String, String> downloadLocalRerankingModel(@PathVariable UUID crateId) throws Exception {
    access.requireMutable(crateId,CrateMember.Role.OWNER);
    try(var ignored=tech.wenisch.contextcrate.config.CrateContext.use(crateId)){reranking.downloadModel();}
    return java.util.Map.of("status", "Local reranking model is ready");
  }
}
