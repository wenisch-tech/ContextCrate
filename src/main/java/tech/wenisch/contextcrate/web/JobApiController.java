package tech.wenisch.contextcrate.web;

import static tech.wenisch.contextcrate.domain.PipelineTypes.RunStatus;

import jakarta.validation.Valid;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import tech.wenisch.contextcrate.domain.*;
import tech.wenisch.contextcrate.service.*;

@RestController
@RequestMapping("/api/v1/crates/{crateId}/jobs")
public class JobApiController {
  private final JobService service;
  private final ConfigurationCodec codec;
  private final CrateAccessService access;

  @org.springframework.beans.factory.annotation.Autowired
  public JobApiController(JobService service, ConfigurationCodec codec, CrateAccessService access) {
    this.service = service;
    this.codec = codec;
    this.access = access;
  }

  @GetMapping
  public List<JobResponse> list(@PathVariable UUID crateId) {
    access.require(crateId, CrateMember.Role.VIEWER);
    return service.jobs(crateId).stream().map(j -> response(j, codec)).toList();
  }

  @GetMapping("/{id}")
  public JobResponse get(@PathVariable UUID crateId, @PathVariable UUID id) {
    access.require(crateId, CrateMember.Role.VIEWER);
    return response(service.requireJob(crateId, id), codec);
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public JobResponse create(@PathVariable UUID crateId, @Valid @RequestBody JobRequest request) {
    access.requireMutable(crateId, CrateMember.Role.EDITOR);
    return response(service.create(crateId, request.name(), request.configuration()), codec);
  }

  @PutMapping("/{id}")
  public JobResponse update(@PathVariable UUID crateId, @PathVariable UUID id, @Valid @RequestBody JobRequest request) {
    access.requireMutable(crateId, CrateMember.Role.EDITOR);
    service.requireJob(crateId, id);
    return response(
        service.update(crateId, id, request.name(), request.configuration(), request.enabled()), codec);
  }

  @PostMapping("/{id}/runs")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public CrawlRun start(@PathVariable UUID crateId, @PathVariable UUID id) {
    access.requireMutable(crateId, CrateMember.Role.EDITOR);
    return service.start(crateId, id);
  }

  @PostMapping("/runs/{id}/{action}")
  public CrawlRun action(@PathVariable UUID crateId, @PathVariable UUID id, @PathVariable String action) {
    access.requireMutable(crateId, CrateMember.Role.EDITOR);
    return service.status(
        crateId, id,
        switch (action.toLowerCase(Locale.ROOT)) {
          case "pause" -> RunStatus.PAUSED;
          case "resume" -> RunStatus.RUNNING;
          case "cancel" -> RunStatus.CANCELLED;
          default -> throw new IllegalArgumentException("Unknown action " + action);
        });
  }

  public record JobRequest(String name, @Valid CrawlConfiguration configuration, boolean enabled) {
    public JobRequest {
      if (name == null || name.isBlank()) throw new IllegalArgumentException("name is required");
      configuration =
          configuration == null ? new CrawlConfiguration(null, null, null, null, null) : configuration;
    }
  }

  public record JobResponse(
      UUID id, String name, boolean enabled, CrawlConfiguration configuration) {}

  private static JobResponse response(CrawlJob j, ConfigurationCodec codec) {
    CrawlConfiguration stored = codec.read(j.getConfigurationJson());
    CrawlConfiguration sanitized =
        new CrawlConfiguration(
            stored.scope(),
            stored.politeness(),
            stored.reliability(),
            stored.output(),
            stored.loginConfiguration().withoutSecrets());
    return new JobResponse(
        j.getId(), j.getName(), j.isEnabled(), sanitized);
  }
}
