package tech.wenisch.contextcrate.web;

import jakarta.validation.Valid;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import tech.wenisch.contextcrate.domain.*;
import tech.wenisch.contextcrate.service.*;

@RestController
@RequestMapping("/api/v1/crates/{crateId}/sources/{sourceId}/ingestion-jobs")
public class IngestionJobApiController {
  private final IngestionService ingestion;
  private final IngestionConfigurationCodec codec;
  private final CrateAccessService access;

  public IngestionJobApiController(IngestionService ingestion, IngestionConfigurationCodec codec,
      CrateAccessService access) {
    this.ingestion = ingestion;
    this.codec = codec;
    this.access = access;
  }

  @GetMapping
  public List<JobResponse> list(@PathVariable UUID crateId, @PathVariable UUID sourceId) {
    access.require(crateId, CrateMember.Role.VIEWER);
    Source source = ingestion.requireSource(crateId, sourceId);
    return ingestion.jobs(crateId, sourceId).stream()
        .map(job -> response(job, source.getConnectorType())).toList();
  }

  @GetMapping("/{jobId}")
  public JobResponse get(@PathVariable UUID crateId, @PathVariable UUID sourceId,
      @PathVariable UUID jobId) {
    access.require(crateId, CrateMember.Role.VIEWER);
    Source source = ingestion.requireSource(crateId, sourceId);
    return response(ingestion.requireJob(crateId, sourceId, jobId), source.getConnectorType());
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public JobResponse create(@PathVariable UUID crateId, @PathVariable UUID sourceId,
      @Valid @RequestBody JobRequest request) {
    access.requireMutable(crateId, CrateMember.Role.EDITOR);
    Source source = ingestion.requireSource(crateId, sourceId);
    return response(ingestion.create(crateId, sourceId, request.name(), request.configuration()),
        source.getConnectorType());
  }

  @PutMapping("/{jobId}")
  public JobResponse update(@PathVariable UUID crateId, @PathVariable UUID sourceId,
      @PathVariable UUID jobId, @Valid @RequestBody JobRequest request) {
    access.requireMutable(crateId, CrateMember.Role.EDITOR);
    Source source = ingestion.requireSource(crateId, sourceId);
    return response(ingestion.update(crateId, sourceId, jobId, request.name(),
        request.configuration(), request.enabled() == null || request.enabled()),
        source.getConnectorType());
  }

  @PostMapping("/{jobId}/runs")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public IngestionRunResponse start(@PathVariable UUID crateId, @PathVariable UUID sourceId,
      @PathVariable UUID jobId) {
    access.requireMutable(crateId, CrateMember.Role.EDITOR);
    IngestionRun run = ingestion.start(crateId, sourceId, jobId);
    return response(run);
  }

  private JobResponse response(IngestionJob job, ConnectorType type) {
    return new JobResponse(job.getId(), job.getSourceId(), job.getName(), job.isEnabled(),
        codec.read(job.getConfigurationJson(), type).withoutSecrets());
  }

  private IngestionRunResponse response(IngestionRun run) {
    return IngestionRunResponse.from(run, ingestion.sourceConfiguration(run),
        ingestion.jobConfiguration(run));
  }

  public record JobRequest(String name, @Valid IngestionConfiguration configuration,
      Boolean enabled) {
    public JobRequest {
      if (name == null || name.isBlank()) throw new IllegalArgumentException("name is required");
    }
  }
  public record JobResponse(UUID id, UUID sourceId, String name, boolean enabled,
      IngestionConfiguration configuration) {}
}
