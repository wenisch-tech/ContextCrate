package tech.wenisch.harvex.web;

import static tech.wenisch.harvex.domain.PipelineTypes.RunStatus;

import jakarta.validation.Valid;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import tech.wenisch.harvex.domain.*;
import tech.wenisch.harvex.service.*;

@RestController
@RequestMapping("/api/v1/jobs")
public class JobApiController {
  private final JobService service;
  private final ConfigurationCodec codec;

  public JobApiController(JobService service, ConfigurationCodec codec) {
    this.service = service;
    this.codec = codec;
  }

  @GetMapping
  public List<JobResponse> list() {
    return service.jobs().stream().map(j -> response(j, codec)).toList();
  }

  @GetMapping("/{id}")
  public JobResponse get(@PathVariable UUID id) {
    return response(service.requireJob(id), codec);
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public JobResponse create(@Valid @RequestBody JobRequest request) {
    return response(service.create(request.name(), request.configuration()), codec);
  }

  @PutMapping("/{id}")
  public JobResponse update(@PathVariable UUID id, @Valid @RequestBody JobRequest request) {
    return response(
        service.update(id, request.name(), request.configuration(), request.enabled()), codec);
  }

  @PostMapping("/{id}/runs")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public CrawlRun start(@PathVariable UUID id) {
    return service.start(id);
  }

  @PostMapping("/runs/{id}/{action}")
  public CrawlRun action(@PathVariable UUID id, @PathVariable String action) {
    return service.status(
        id,
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
          configuration == null ? new CrawlConfiguration(null, null, null, null) : configuration;
    }
  }

  public record JobResponse(
      UUID id, String name, boolean enabled, CrawlConfiguration configuration) {}

  private static JobResponse response(CrawlJob j, ConfigurationCodec codec) {
    return new JobResponse(
        j.getId(), j.getName(), j.isEnabled(), codec.read(j.getConfigurationJson()));
  }
}
