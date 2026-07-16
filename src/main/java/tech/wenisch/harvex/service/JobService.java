package tech.wenisch.harvex.service;

import static tech.wenisch.harvex.domain.PipelineTypes.*;

import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tech.wenisch.harvex.crawl.UrlPolicy;
import tech.wenisch.harvex.domain.*;
import tech.wenisch.harvex.queue.*;
import tech.wenisch.harvex.repository.*;

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
  public CrawlJob create(String name, CrawlConfiguration config) {
    String canonical = urls.canonicalize(config.scope().seedUrl());
    if (!urls.inScope(canonical, config.scope()))
      throw new IllegalArgumentException("Seed URL is outside configured scope");
    var job = jobs.save(new CrawlJob(UUID.randomUUID(), name, codec.write(config)));
    audits.save(
        new AuditLog(actor(), "JOB_CREATED", job.getId().toString(), "Created crawl job " + name));
    if (!config.politeness().honorRobots())
      audits.save(
          new AuditLog(
              actor(),
              "ROBOTS_OVERRIDE",
              job.getId().toString(),
              "robots.txt enforcement disabled"));
    return job;
  }

  @Transactional
  public CrawlJob update(UUID id, String name, CrawlConfiguration config, boolean enabled) {
    var job = requireJob(id);
    job.update(name, codec.write(config), enabled);
    return jobs.save(job);
  }

  @Transactional
  public CrawlRun start(UUID jobId) {
    var job = requireJob(jobId);
    if (!job.isEnabled()) throw new IllegalStateException("Job is disabled");
    var config = codec.read(job.getConfigurationJson());
    var run = runs.save(new CrawlRun(UUID.randomUUID(), jobId, job.getConfigurationJson()));
    String canonical = urls.canonicalize(config.scope().seedUrl());
    var entry =
        frontier.save(
            new FrontierEntry(
                UUID.randomUUID(), run.getId(), config.scope().seedUrl(), canonical, 0));
    entry.status(FrontierStatus.QUEUED);
    frontier.save(entry);
    queue.publish(
        PipelineMessage.create(
            WorkStage.FETCH,
            payload(run.getId(), entry.getId()),
            run.getId(),
            "fetch:" + entry.getId(),
            100));
    return run;
  }

  @Transactional
  public CrawlRun status(UUID id, RunStatus status) {
    var run = runs.findById(id).orElseThrow();
    run.status(status);
    return runs.save(run);
  }

  public List<CrawlJob> jobs() {
    return jobs.findAll();
  }

  public CrawlJob requireJob(UUID id) {
    return jobs.findById(id).orElseThrow();
  }

  public List<CrawlRun> runs() {
    return runs.findTop20ByOrderByStartedAtDesc();
  }

  public CrawlRun requireRun(UUID id) {
    return runs.findById(id).orElseThrow();
  }

  public static String payload(UUID runId, UUID entityId) {
    return "{\"runId\":\"" + runId + "\",\"entityId\":\"" + entityId + "\"}";
  }

  private static String actor() {
    var auth =
        org.springframework.security.core.context.SecurityContextHolder.getContext()
            .getAuthentication();
    return auth == null ? "system" : auth.getName();
  }
}
