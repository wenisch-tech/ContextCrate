package tech.wenisch.contextcrate.service;

import static tech.wenisch.contextcrate.domain.PipelineTypes.*;

import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tech.wenisch.contextcrate.crawl.UrlPolicy;
import tech.wenisch.contextcrate.domain.*;
import tech.wenisch.contextcrate.queue.*;
import tech.wenisch.contextcrate.repository.*;

@Service
public class IngestionService {
  private final SourceRepository sources;
  private final IngestionJobRepository jobs;
  private final IngestionRunRepository runs;
  private final SourceItemRepository items;
  private final PipelineQueue queue;
  private final SourceConfigurationCodec sourceCodec;
  private final IngestionConfigurationCodec jobCodec;
  private final UrlPolicy urls;
  private final AuditLogRepository audits;

  public IngestionService(SourceRepository sources, IngestionJobRepository jobs,
      IngestionRunRepository runs, SourceItemRepository items, PipelineQueue queue,
      SourceConfigurationCodec sourceCodec, IngestionConfigurationCodec jobCodec,
      UrlPolicy urls, AuditLogRepository audits) {
    this.sources = sources;
    this.jobs = jobs;
    this.runs = runs;
    this.items = items;
    this.queue = queue;
    this.sourceCodec = sourceCodec;
    this.jobCodec = jobCodec;
    this.urls = urls;
    this.audits = audits;
  }

  @Transactional
  public IngestionJob create(UUID crateId, UUID sourceId, String name,
      IngestionConfiguration configuration) {
    Source source = requireSource(crateId, sourceId);
    validate(source, configuration);
    IngestionJob job = jobs.save(new IngestionJob(UUID.randomUUID(), crateId, sourceId, name,
        jobCodec.write(configuration)));
    audits.save(new AuditLog(crateId, actor(), "INGESTION_JOB_CREATED", job.getId().toString(),
        "Created ingestion job " + name));
    return job;
  }

  @Transactional
  public IngestionJob update(UUID crateId, UUID sourceId, UUID id, String name,
      IngestionConfiguration configuration, boolean enabled) {
    Source source = requireSource(crateId, sourceId);
    IngestionJob job = requireJob(crateId, sourceId, id);
    IngestionConfiguration retained = retainSecrets(source.getConnectorType(),
        jobCodec.read(job.getConfigurationJson(), source.getConnectorType()), configuration);
    validate(source, retained);
    job.update(name, jobCodec.write(retained), enabled);
    return jobs.save(job);
  }

  @Transactional
  public IngestionRun start(UUID crateId, UUID sourceId, UUID jobId) {
    Source source = requireSource(crateId, sourceId);
    IngestionJob job = requireJob(crateId, sourceId, jobId);
    if (!source.isEnabled()) throw new IllegalStateException("Source is disabled");
    if (!job.isEnabled()) throw new IllegalStateException("Ingestion job is disabled");
    ConnectorType type = source.getConnectorType();
    IngestionConfiguration config = jobCodec.read(job.getConfigurationJson(), type);
    validate(source, config);
    IngestionRun run = runs.save(new IngestionRun(UUID.randomUUID(), crateId, sourceId, jobId,
        source.getConfigurationJson(), job.getConfigurationJson()));
    String locator;
    String sourceUri;
    WorkStage stage;
    if (type == ConnectorType.HTTPS) {
      CrawlConfiguration web = effectiveWeb(run);
      locator = web.scope().seedUrl();
      sourceUri = urls.canonicalize(locator);
      stage = WorkStage.WEB_FETCH;
    } else {
      SourceConfiguration sourceConfig = sourceCodec.read(source.getConfigurationJson(), type);
      locator = sourceConfig.git().repositoryUrl();
      sourceUri = "git:" + locator;
      stage = WorkStage.GIT_FETCH;
    }
    SourceItem item = new SourceItem(UUID.randomUUID(), run.getId(), locator, sourceUri, 0);
    item.assignCrate(crateId);
    item.status(FrontierStatus.QUEUED);
    items.save(item);
    queue.publish(PipelineMessage.create(crateId, stage,
        payload(crateId, run.getId(), item.getId()), run.getId(),
        crateId + ":" + stage.name().toLowerCase(Locale.ROOT) + ":" + item.getId(), 100));
    return run;
  }

  public CrawlConfiguration effectiveWeb(IngestionRun run) {
    Source source = sources.findById(run.getSourceId()).orElseThrow();
    IngestionConfiguration jobConfig =
        jobCodec.read(run.getJobConfigurationJson(), source.getConnectorType());
    return jobConfig.webCrawler();
  }

  public SourceConfiguration sourceConfiguration(IngestionRun run) {
    Source source = sources.findById(run.getSourceId()).orElseThrow();
    return sourceCodec.read(run.getSourceConfigurationJson(), source.getConnectorType());
  }

  public IngestionConfiguration jobConfiguration(IngestionRun run) {
    Source source = sources.findById(run.getSourceId()).orElseThrow();
    return jobCodec.read(run.getJobConfigurationJson(), source.getConnectorType());
  }

  public ConnectorType connector(IngestionRun run) {
    return sources.findById(run.getSourceId()).orElseThrow().getConnectorType();
  }

  @Transactional
  public IngestionRun status(UUID crateId, UUID id, RunStatus status) {
    IngestionRun run = requireRun(crateId, id);
    run.status(status);
    return runs.save(run);
  }

  public List<IngestionJob> jobs(UUID crateId, UUID sourceId) {
    requireSource(crateId, sourceId);
    return jobs.findBySourceIdOrderByCreatedAtDesc(sourceId);
  }
  public List<IngestionJob> allJobs(UUID crateId) {
    return jobs.findByCrateIdOrderByCreatedAtDesc(crateId);
  }
  public IngestionJob requireJob(UUID crateId, UUID sourceId, UUID id) {
    return jobs.findByIdAndSourceIdAndCrateId(id, sourceId, crateId).orElseThrow();
  }
  public IngestionJob requireJob(UUID crateId, UUID id) {
    return jobs.findByIdAndCrateId(id, crateId).orElseThrow();
  }
  public List<IngestionRun> runs(UUID crateId) {
    return runs.findTop20ByCrateIdOrderByStartedAtDesc(crateId);
  }
  public IngestionRun requireRun(UUID crateId, UUID id) {
    return runs.findByIdAndCrateId(id, crateId).orElseThrow();
  }
  public Source requireSource(UUID crateId, UUID sourceId) {
    return sources.findByIdAndCrateId(sourceId, crateId).orElseThrow();
  }

  public static String payload(UUID crateId, UUID runId, UUID entityId) {
    return "{\"crateId\":\"" + crateId + "\",\"runId\":\"" + runId
        + "\",\"entityId\":\"" + entityId + "\"}";
  }

  private void validate(Source source, IngestionConfiguration config) {
    if (config == null) throw new IllegalArgumentException("configuration is required");
    if (source.getConnectorType() == ConnectorType.HTTPS) {
      if (config.webCrawler() == null)
        throw new IllegalArgumentException("HTTPS ingestion configuration is required");
      String canonical = urls.canonicalize(config.webCrawler().scope().seedUrl());
      if (!urls.inScope(canonical, config.webCrawler().scope()))
        throw new IllegalArgumentException("Seed URL is outside configured scope");
      SourceConfiguration sourceConfiguration = sourceCodec.read(source.getConfigurationJson(),
          source.getConnectorType());
      String sourceHost = java.net.URI.create(sourceConfiguration.website().url()).getHost();
      String seedHost = java.net.URI.create(canonical).getHost();
      if (!sourceHost.equalsIgnoreCase(seedHost))
        throw new IllegalArgumentException("HTTPS ingestion job seed URL must use the source host");
      CrawlConfiguration.LoginConfiguration login = config.webCrawler().loginConfiguration();
      if (login.authMethod() != CrawlConfiguration.AuthMethod.NONE && !login.isConfigured())
        throw new IllegalArgumentException("Selected HTTPS authentication is incomplete");
    } else if (config.git() == null) {
      throw new IllegalArgumentException("git configuration is required");
    }
  }

  private static IngestionConfiguration retainSecrets(ConnectorType type,
      IngestionConfiguration existing, IngestionConfiguration submitted) {
    if (type == ConnectorType.GIT && existing.git() != null && submitted != null
        && submitted.git() != null && (submitted.git().token() == null
            || submitted.git().token().isBlank())) {
      IngestionConfiguration.Git old = existing.git();
      IngestionConfiguration.Git replacement = submitted.git();
      return IngestionConfiguration.git(new IngestionConfiguration.Git(replacement.ref(),
          replacement.username(), old.token(), replacement.includePatterns(),
          replacement.excludePatterns(), replacement.maxFiles(), replacement.maxFileBytes(),
          replacement.output()));
    }
    if (type != ConnectorType.HTTPS || existing.webCrawler() == null
        || submitted == null || submitted.webCrawler() == null) return submitted;
    CrawlConfiguration current = existing.webCrawler();
    CrawlConfiguration replacement = submitted.webCrawler();
    CrawlConfiguration.LoginConfiguration oldLogin = current.loginConfiguration();
    CrawlConfiguration.LoginConfiguration login = replacement.loginConfiguration();
    if (login.authMethod() == oldLogin.authMethod()) {
      String password = login.password() == null || login.password().isBlank()
          ? oldLogin.password() : login.password();
      String clientSecret = login.clientSecret() == null || login.clientSecret().isBlank()
          ? oldLogin.clientSecret() : login.clientSecret();
      login = new CrawlConfiguration.LoginConfiguration(login.loginPageUrl(), login.username(),
          password, login.usernameField(), login.passwordField(), login.submitSelector(),
          login.successDetection(), login.directLogin(), login.authServerUrl(), login.clientId(),
          clientSecret, login.realm(), login.authMethod());
    }
    return IngestionConfiguration.web(new CrawlConfiguration(replacement.scope(),
        replacement.politeness(), replacement.reliability(), replacement.output(), login));
  }

  private static String actor() {
    var authentication = org.springframework.security.core.context.SecurityContextHolder.getContext()
        .getAuthentication();
    return authentication == null ? "system" : authentication.getName();
  }
}
