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
public class JobService {
  private final CrawlJobRepository jobs;
  private final CrawlRunRepository runs;
  private final FrontierEntryRepository frontier;
  private final PipelineQueue queue;
  private final ConfigurationCodec codec;
  private final UrlPolicy urls;
  private final AuditLogRepository audits;

  public JobService(
      CrawlJobRepository jobs,
      CrawlRunRepository runs,
      FrontierEntryRepository frontier,
      PipelineQueue queue,
      ConfigurationCodec codec,
      UrlPolicy urls,
      AuditLogRepository audits) {
    this.jobs = jobs;
    this.runs = runs;
    this.frontier = frontier;
    this.queue = queue;
    this.codec = codec;
    this.urls = urls;
    this.audits = audits;
  }

  @Transactional
  public CrawlJob create(UUID crateId, String name, CrawlConfiguration config) {
    requireValidAuthentication(config.loginConfiguration());
    String canonical = urls.canonicalize(config.scope().seedUrl());
    if (!urls.inScope(canonical, config.scope()))
      throw new IllegalArgumentException("Seed URL is outside configured scope");
    var value = new CrawlJob(UUID.randomUUID(), name, codec.write(config));
    value.assignCrate(crateId);
    var job = jobs.save(value);
    audits.save(
        new AuditLog(crateId, actor(), "JOB_CREATED", job.getId().toString(), "Created crawl job " + name));
    if (!config.politeness().honorRobots())
      audits.save(
          new AuditLog(
              crateId,
              actor(),
              "ROBOTS_OVERRIDE",
              job.getId().toString(),
              "robots.txt enforcement disabled"));
    return job;
  }

  @Transactional
  public CrawlJob update(UUID crateId, UUID id, String name, CrawlConfiguration config, boolean enabled) {
    var job = requireJob(crateId, id);
    config = retainUnchangedSecrets(codec.read(job.getConfigurationJson()), config);
    requireValidAuthentication(config.loginConfiguration());
    job.update(name, codec.write(config), enabled);
    return jobs.save(job);
  }

  @Transactional
  public CrawlRun start(UUID crateId, UUID jobId) {
    var job = requireJob(crateId, jobId);
    if (!job.isEnabled()) throw new IllegalStateException("Job is disabled");
    var config = codec.read(job.getConfigurationJson());
    var runValue = new CrawlRun(UUID.randomUUID(), jobId, job.getConfigurationJson());
    runValue.assignCrate(job.getCrateId());
    var run = runs.save(runValue);
    String canonical = urls.canonicalize(config.scope().seedUrl());
    var entry = new FrontierEntry(
        UUID.randomUUID(), run.getId(), config.scope().seedUrl(), canonical, 0);
    entry.assignCrate(job.getCrateId());
    entry = frontier.save(entry);
    entry.status(FrontierStatus.QUEUED);
    frontier.save(entry);
    queue.publish(
        PipelineMessage.create(
            job.getCrateId(),
            WorkStage.FETCH,
            payload(job.getCrateId(), run.getId(), entry.getId()),
            run.getId(),
            job.getCrateId() + ":fetch:" + entry.getId(),
            100));
    return run;
  }

  @Transactional
  public CrawlRun status(UUID crateId, UUID id, RunStatus status) {
    var run = requireRun(crateId, id);
    run.status(status);
    return runs.save(run);
  }

  public List<CrawlJob> jobs(UUID crateId) {
    return jobs.findByCrateIdOrderByCreatedAtDesc(crateId);
  }

  public CrawlJob requireJob(UUID crateId, UUID id) {
    return jobs.findByIdAndCrateId(id, crateId).orElseThrow();
  }

  public List<CrawlRun> runs(UUID crateId) {
    return runs.findTop20ByCrateIdOrderByStartedAtDesc(crateId);
  }

  public CrawlRun requireRun(UUID crateId, UUID id) {
    return runs.findByIdAndCrateId(id, crateId).orElseThrow();
  }

  public static String payload(UUID crateId, UUID runId, UUID entityId) {
    return "{\"crateId\":\"" + crateId + "\",\"runId\":\"" + runId
        + "\",\"entityId\":\"" + entityId + "\"}";
  }

  private static String actor() {
    var auth =
        org.springframework.security.core.context.SecurityContextHolder.getContext()
            .getAuthentication();
    return auth == null ? "system" : auth.getName();
  }

  private static CrawlConfiguration retainUnchangedSecrets(
      CrawlConfiguration existing, CrawlConfiguration submitted) {
    var oldLogin = existing.loginConfiguration();
    var login = submitted.loginConfiguration();
    if (login.authMethod() == CrawlConfiguration.AuthMethod.FORM
        && oldLogin.authMethod() == CrawlConfiguration.AuthMethod.FORM
        && (login.password() == null || login.password().isBlank())) {
      login =
          new CrawlConfiguration.LoginConfiguration(
              login.loginPageUrl(),
              login.username(),
              oldLogin.password(),
              login.usernameField(),
              login.passwordField(),
              login.submitSelector(),
              login.successDetection(),
              login.directLogin(),
              null,
              null,
              null,
              null,
              login.authMethod());
    } else if (login.authMethod() == CrawlConfiguration.AuthMethod.OAUTH2
        && oldLogin.authMethod() == CrawlConfiguration.AuthMethod.OAUTH2
        && (login.clientSecret() == null || login.clientSecret().isBlank())) {
      login =
          new CrawlConfiguration.LoginConfiguration(
              null,
              null,
              null,
              login.usernameField(),
              login.passwordField(),
              login.submitSelector(),
              login.successDetection(),
              login.directLogin(),
              login.authServerUrl(),
              login.clientId(),
              oldLogin.clientSecret(),
              login.realm(),
              login.authMethod());
    }
    return new CrawlConfiguration(
        submitted.scope(),
        submitted.politeness(),
        submitted.reliability(),
        submitted.output(),
        login);
  }

  private static void requireValidAuthentication(
      CrawlConfiguration.LoginConfiguration login) {
    if (login.authMethod() != CrawlConfiguration.AuthMethod.NONE && !login.isConfigured()) {
      throw new IllegalArgumentException("Selected crawler authentication is incomplete");
    }
  }
}
