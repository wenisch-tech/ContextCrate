package tech.wenisch.contextcrate.web;

import static tech.wenisch.contextcrate.domain.PipelineTypes.WorkStage;

import java.util.*;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import tech.wenisch.contextcrate.config.ContextCrateProperties;
import tech.wenisch.contextcrate.domain.CrawlConfiguration;
import tech.wenisch.contextcrate.domain.CrawlJob;
import tech.wenisch.contextcrate.domain.PipelineTypes.ExtractionType;
import tech.wenisch.contextcrate.index.SearchIndex;
import tech.wenisch.contextcrate.queue.PipelineQueue;
import tech.wenisch.contextcrate.repository.*;
import tech.wenisch.contextcrate.service.*;
import tech.wenisch.contextcrate.answer.RagSettingsService;
import tech.wenisch.contextcrate.config.RuntimeProviderSettings;
import tech.wenisch.contextcrate.embedding.LocalOnnxEmbeddingProvider;
import tech.wenisch.contextcrate.domain.*;

@Controller
@RequestMapping("/crates/{crateId}")
public class UiController {
  private final JobService jobs;
  private final ConfigurationCodec codec;
  private final ContextCrateProperties properties;
  private final PipelineQueue queue;
  private final SearchIndex index;
  private final NormalizedDocumentRepository documents;
  private final ExtractionService extraction;
  private final RagSettingsService ragSettings;
  private final RuntimeProviderSettings providerSettings;
  private final LocalOnnxEmbeddingProvider localEmbeddings;
  private final FetchRecordRepository fetchRecords;
  private final FrontierEntryRepository frontierEntries;
  private final CrateService crates;
  private final CrateAccessService access;
  private final IndexRebuildService rebuild;

  public UiController(
      JobService jobs,
      ConfigurationCodec codec,
      ContextCrateProperties properties,
      PipelineQueue queue,
      SearchIndex index,
      NormalizedDocumentRepository documents,
      ExtractionService extraction,
      RagSettingsService ragSettings,
      RuntimeProviderSettings providerSettings,
      LocalOnnxEmbeddingProvider localEmbeddings,
      FetchRecordRepository fetchRecords,
      FrontierEntryRepository frontierEntries, CrateService crates, CrateAccessService access,
      IndexRebuildService rebuild) {
    this.jobs = jobs;
    this.codec = codec;
    this.properties = properties;
    this.queue = queue;
    this.index = index;
    this.documents = documents;
    this.extraction = extraction;
    this.ragSettings = ragSettings;
    this.providerSettings = providerSettings;
    this.localEmbeddings = localEmbeddings;
    this.fetchRecords = fetchRecords;
    this.frontierEntries = frontierEntries;
    this.crates = crates;
    this.access = access;
    this.rebuild = rebuild;
  }

  @ModelAttribute
  void crate(@PathVariable UUID crateId, Model model) {
    model.addAttribute("crate", crates.require(crateId, CrateMember.Role.VIEWER));
    model.addAttribute("crates", crates.accessible());
    model.addAttribute("adminElevation", access.activeElevation(crateId).orElse(null));
  }

  @GetMapping("/")
  String dashboard(@PathVariable UUID crateId,@RequestParam(defaultValue = "") String q, Model model) throws Exception {
    var allJobs = jobs.jobs(crateId);
    model.addAttribute("jobs", allJobs);
    model.addAttribute("runs", jobs.runs(crateId));
    model.addAttribute(
        "jobNames",
        allJobs.stream()
            .collect(java.util.stream.Collectors.toMap(CrawlJob::getId, CrawlJob::getName)));
    model.addAttribute("properties", properties);
    model.addAttribute("indexHealth", index.health(crateId));
    model.addAttribute(
        "queueDepth",
        Arrays.stream(WorkStage.values())
            .collect(java.util.stream.Collectors.toMap(Enum::name, queue::depth)));
    model.addAttribute("documentCount", documents.findByCrateId(crateId).size());
        model.addAttribute("searchQuery", q);
        model.addAttribute(
          "searchResults",
          q.isBlank() ? null : index.search(new SearchIndex.SearchRequest(crateId,q, 10, null, null, null)));
    return "dashboard";
  }

  @GetMapping("/jobs")
  String jobList(@PathVariable UUID crateId,Model model) {
    model.addAttribute("jobs", jobs.jobs(crateId));
    model.addAttribute("codec", codec);
    return "jobs";
  }

  @GetMapping("/jobs/new")
  String newJob() {
    return "job-form";
  }

  @GetMapping("/jobs/{id}/edit")
  String editJob(@PathVariable UUID crateId,@PathVariable UUID id, Model model) {
    var job = jobs.requireJob(crateId,id);
    var config = codec.read(job.getConfigurationJson());
    model.addAttribute("job", job);
    model.addAttribute("config", config);
    return "job-edit";
  }

    @PostMapping("/jobs")
    String create(
        @PathVariable UUID crateId,@RequestParam String name,
        @RequestParam String seedUrl,
        @RequestParam String allowedHost,
        @RequestParam int maxDepth,
        @RequestParam int maxPages,
        @RequestParam(defaultValue = "false") boolean allowSubdomains,
        @RequestParam(defaultValue = "ContextCrateBot/0.1") String userAgent,
        @RequestParam(defaultValue = "") String contact,
        @RequestParam(defaultValue = "false") boolean honorRobots,
        @RequestParam(defaultValue = "1000") long delayMillis,
        @RequestParam(defaultValue = "15000") int timeoutMillis,
        @RequestParam(defaultValue = "3") int maxAttempts,
        @RequestParam(defaultValue = "1000") long backoffMillis,
        @RequestParam(defaultValue = "10") long maxBodyMegabytes,
        @RequestParam(defaultValue = "AUTO") CrawlConfiguration.RenderMode renderMode,
        @RequestParam(defaultValue = "30") int retentionDays,
        @RequestParam(defaultValue = "") String contentSelector,
        @RequestParam(defaultValue = "2000") int chunkSize,
        @RequestParam(defaultValue = "200") int chunkOverlap,
        @RequestParam(defaultValue = "") String authUsername,
        @RequestParam(defaultValue = "") String authPassword,
        @RequestParam(defaultValue = "") String authLoginPageUrl,
        @RequestParam(defaultValue = "false") boolean authDirectLogin,
        @RequestParam(defaultValue = "NONE") CrawlConfiguration.AuthMethod authMethod,
        @RequestParam(defaultValue = "") String authServerUrl,
        @RequestParam(defaultValue = "") String authRealm,
        @RequestParam(defaultValue = "") String authClientId,
        @RequestParam(defaultValue = "") String authClientSecret) {
      var c =
          configurationFromForm(
              null,
              seedUrl,
              allowedHost,
              maxDepth,
              maxPages,
              allowSubdomains,
              userAgent,
              contact,
              honorRobots,
              delayMillis,
              timeoutMillis,
              maxAttempts,
              backoffMillis,
              maxBodyMegabytes,
              renderMode,
              retentionDays,
              contentSelector,
              chunkSize,
              chunkOverlap,
              authUsername,
              authPassword,
              authLoginPageUrl,
              authDirectLogin,
              authMethod,
              authServerUrl,
              authRealm,
              authClientId,
              authClientSecret);
      access.requireMutable(crateId,CrateMember.Role.EDITOR);
      jobs.create(crateId,name, c);
      return "redirect:/crates/"+crateId+"/jobs";
    }

  @PostMapping("/jobs/{id}/update")
  String updateJob(
      @PathVariable UUID crateId,@PathVariable UUID id,
      @RequestParam String name,
      @RequestParam String seedUrl,
      @RequestParam String allowedHost,
      @RequestParam int maxDepth,
      @RequestParam int maxPages,
      @RequestParam(defaultValue = "false") boolean allowSubdomains,
      @RequestParam(defaultValue = "ContextCrateBot/0.1") String userAgent,
      @RequestParam(defaultValue = "") String contact,
      @RequestParam(defaultValue = "false") boolean honorRobots,
      @RequestParam(defaultValue = "1000") long delayMillis,
      @RequestParam(defaultValue = "15000") int timeoutMillis,
      @RequestParam(defaultValue = "3") int maxAttempts,
      @RequestParam(defaultValue = "1000") long backoffMillis,
      @RequestParam(defaultValue = "10") long maxBodyMegabytes,
      @RequestParam(defaultValue = "AUTO") CrawlConfiguration.RenderMode renderMode,
      @RequestParam(defaultValue = "30") int retentionDays,
      @RequestParam(defaultValue = "") String contentSelector,
      @RequestParam(defaultValue = "2000") int chunkSize,
      @RequestParam(defaultValue = "200") int chunkOverlap,
      @RequestParam(defaultValue = "") String authUsername,
      @RequestParam(defaultValue = "") String authPassword,
      @RequestParam(defaultValue = "") String authLoginPageUrl,
      @RequestParam(defaultValue = "false") boolean authDirectLogin,
      @RequestParam(defaultValue = "NONE") CrawlConfiguration.AuthMethod authMethod,
      @RequestParam(defaultValue = "") String authServerUrl,
      @RequestParam(defaultValue = "") String authRealm,
      @RequestParam(defaultValue = "") String authClientId,
      @RequestParam(defaultValue = "") String authClientSecret) {
    access.requireMutable(crateId,CrateMember.Role.EDITOR);
    var job = jobs.requireJob(crateId,id);
    var existing = codec.read(job.getConfigurationJson());
    var c =
        configurationFromForm(
            existing,
            seedUrl,
            allowedHost,
            maxDepth,
            maxPages,
            allowSubdomains,
            userAgent,
            contact,
            honorRobots,
            delayMillis,
            timeoutMillis,
            maxAttempts,
            backoffMillis,
            maxBodyMegabytes,
            renderMode,
            retentionDays,
            contentSelector,
            chunkSize,
            chunkOverlap,
            authUsername,
            authPassword,
            authLoginPageUrl,
            authDirectLogin,
            authMethod,
            authServerUrl,
            authRealm,
            authClientId,
            authClientSecret);
    jobs.update(crateId, id, name, c, job.isEnabled());
    return "redirect:/crates/"+crateId+"/jobs";
  }

  @PostMapping("/jobs/{id}/start")
  String start(@PathVariable UUID crateId,@PathVariable UUID id) {
    access.requireMutable(crateId,CrateMember.Role.EDITOR);
    jobs.start(crateId,id);
    return "redirect:/crates/"+crateId;
  }

  @GetMapping("/runs/{id}")
  String runDetails(@PathVariable UUID crateId,@PathVariable UUID id, Model model) throws Exception {
    var run = jobs.requireRun(crateId,id);
    var job = jobs.requireJob(crateId,run.getJobId());
    var config = codec.read(run.getConfigurationJson());

    model.addAttribute("run", run);
    model.addAttribute("job", job);
    model.addAttribute("config", config);

    model.addAttribute("fetches", fetchRecords.findTop100ByRunIdOrderByFetchedAtDesc(id));
    model.addAttribute("frontierTotal", frontierEntries.countByRunId(id));
    model.addAttribute(
        "frontierFetched",
        frontierEntries.countByRunIdAndStatus(
            id, tech.wenisch.contextcrate.domain.PipelineTypes.FrontierStatus.FETCHED));
    model.addAttribute(
        "frontierFailed",
        frontierEntries.countByRunIdAndStatus(
            id, tech.wenisch.contextcrate.domain.PipelineTypes.FrontierStatus.FAILED));

    return "run-details";
  }

  private static CrawlConfiguration configurationFromForm(
      CrawlConfiguration existing,
      String seedUrl,
      String allowedHost,
      int maxDepth,
      int maxPages,
      boolean allowSubdomains,
      String userAgent,
      String contact,
      boolean honorRobots,
      long delayMillis,
      int timeoutMillis,
      int maxAttempts,
      long backoffMillis,
      long maxBodyMegabytes,
      CrawlConfiguration.RenderMode renderMode,
      int retentionDays,
      String contentSelector,
      int chunkSize,
      int chunkOverlap,
      String authUsername,
      String authPassword,
      String authLoginPageUrl,
      boolean authDirectLogin,
      CrawlConfiguration.AuthMethod authMethod,
      String authServerUrl,
      String authRealm,
      String authClientId,
      String authClientSecret) {
    CrawlConfiguration defaults = new CrawlConfiguration(null, null, null, null, null);
    CrawlConfiguration base = existing == null ? defaults : existing;

    Set<String> allowedHosts =
        existing == null
            ? new LinkedHashSet<>()
            : new LinkedHashSet<>(base.scope().allowedHosts());
    allowedHosts.add(allowedHost);
    var oldLogin = base.loginConfiguration();
    var login =
        switch (authMethod) {
          case NONE -> CrawlConfiguration.LoginConfiguration.defaults();
          case FORM ->
              new CrawlConfiguration.LoginConfiguration(
                  blankToNull(authLoginPageUrl),
                  blankToNull(authUsername),
                  blankToNull(authPassword),
                  oldLogin.authMethod() == CrawlConfiguration.AuthMethod.FORM
                      ? oldLogin.usernameField()
                      : "username",
                  oldLogin.authMethod() == CrawlConfiguration.AuthMethod.FORM
                      ? oldLogin.passwordField()
                      : "password",
                  oldLogin.authMethod() == CrawlConfiguration.AuthMethod.FORM
                      ? oldLogin.submitSelector()
                      : "button[type='submit']",
                  oldLogin.authMethod() == CrawlConfiguration.AuthMethod.FORM
                      ? oldLogin.successDetection()
                      : new CrawlConfiguration.SuccessDetection(null, null),
                  authDirectLogin,
                  null,
                  null,
                  null,
                  null,
                  authMethod);
          case OAUTH2 ->
              new CrawlConfiguration.LoginConfiguration(
                  null,
                  null,
                  null,
                  "username",
                  "password",
                  "button[type='submit']",
                  new CrawlConfiguration.SuccessDetection(null, null),
                  false,
                  blankToNull(authServerUrl),
                  blankToNull(authClientId),
                  blankToNull(authClientSecret),
                  blankToNull(authRealm),
                  authMethod);
        };

    return new CrawlConfiguration(
        new CrawlConfiguration.Scope(
            seedUrl,
            allowedHosts,
            base.scope().includePatterns(),
            base.scope().excludePatterns(),
            maxDepth,
            maxPages,
            allowSubdomains,
            base.scope().discoverSitemaps()),
        new CrawlConfiguration.Politeness(
            userAgent,
            contact,
            honorRobots,
            base.politeness().perHostConcurrency(),
            delayMillis,
            timeoutMillis),
        new CrawlConfiguration.Reliability(
            maxAttempts,
            backoffMillis,
            maxBodyMegabytes * 1_000_000,
            base.reliability().deduplicateContent(),
            renderMode),
        new CrawlConfiguration.Output(
            retentionDays,
            contentSelector,
            base.output().removeSelectors(),
            chunkSize,
            chunkOverlap,
            base.output().logicalIndex()),
        login);
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value;
  }

  @GetMapping("/documents")
  String docs(@PathVariable UUID crateId,Model model) {
    model.addAttribute("documents", documents.findTop100ByCrateIdOrderByCreatedAtDesc(crateId));
    return "documents";
  }

  @GetMapping("/extractions")
  String extractions(@PathVariable UUID crateId,Model model) {
    model.addAttribute("rules", extraction.rules(crateId));
    model.addAttribute("types", ExtractionType.values());
    model.addAttribute("runs", jobs.runs(crateId));
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
    access.requireMutable(crateId,CrateMember.Role.EDITOR);jobs.requireRun(crateId,id);
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
      @RequestParam(defaultValue="false") boolean answeringEnabled, @RequestParam(required=false) String answeringBaseUrl,
      @RequestParam(required=false) String answeringModel, @RequestParam(required=false) String answeringApiKey) {
    access.requireMutable(crateId,CrateMember.Role.OWNER);
    var previous = providerSettings.effectiveEmbedding(crateId);
    providerSettings.update(crateId,new RuntimeProviderSettings.ProviderForm(embeddingsEnabled,embeddingProvider,embeddingModelId,embeddingRevision,embeddingDownloadUrl,embeddingCachePath,embeddingModelPath,embeddingBaseUrl,embeddingRemoteModel,embeddingApiKey,embeddingDimensions,answeringEnabled,answeringBaseUrl,answeringModel,answeringApiKey));
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
}
