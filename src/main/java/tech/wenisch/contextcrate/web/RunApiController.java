package tech.wenisch.contextcrate.web;

import static tech.wenisch.contextcrate.domain.PipelineTypes.RunStatus;

import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import tech.wenisch.contextcrate.domain.*;
import tech.wenisch.contextcrate.repository.*;
import tech.wenisch.contextcrate.service.*;
import tech.wenisch.contextcrate.storage.ArtifactStore;

@RestController
@RequestMapping("/api/v1/crates/{crateId}/runs")
public class RunApiController {
  private final IngestionService ingestion;
  private final SourceItemRepository items;
  private final AcquisitionRecordRepository acquisitions;
  private final ArtifactStore artifacts;
  private final CrateAccessService access;

  public RunApiController(IngestionService ingestion, SourceItemRepository items,
      AcquisitionRecordRepository acquisitions, ArtifactStore artifacts,
      CrateAccessService access) {
    this.ingestion = ingestion;
    this.items = items;
    this.acquisitions = acquisitions;
    this.artifacts = artifacts;
    this.access = access;
  }

  @GetMapping
  public List<IngestionRunResponse> runs(@PathVariable UUID crateId) {
    access.require(crateId, CrateMember.Role.VIEWER);
    return ingestion.runs(crateId).stream().map(this::response).toList();
  }
  @GetMapping("/{id}")
  public IngestionRunResponse run(@PathVariable UUID crateId, @PathVariable UUID id) {
    access.require(crateId, CrateMember.Role.VIEWER);
    return response(ingestion.requireRun(crateId, id));
  }
  @GetMapping("/{id}/source-items")
  public List<SourceItem> items(@PathVariable UUID crateId, @PathVariable UUID id) {
    access.require(crateId, CrateMember.Role.VIEWER);
    ingestion.requireRun(crateId, id);
    return items.findTop100ByRunIdOrderByDiscoveredAtDesc(id);
  }
  @GetMapping("/{id}/acquisitions")
  public List<AcquisitionRecord> acquisitions(@PathVariable UUID crateId,
      @PathVariable UUID id) {
    access.require(crateId, CrateMember.Role.VIEWER);
    ingestion.requireRun(crateId, id);
    return acquisitions.findTop100ByRunIdOrderByFetchedAtDesc(id);
  }
  @PostMapping("/{id}/{action}")
  public IngestionRunResponse action(@PathVariable UUID crateId, @PathVariable UUID id,
      @PathVariable String action) {
    access.requireMutable(crateId, CrateMember.Role.EDITOR);
    return response(ingestion.status(crateId, id, switch (action.toLowerCase(Locale.ROOT)) {
      case "pause" -> RunStatus.PAUSED;
      case "resume" -> RunStatus.RUNNING;
      case "cancel" -> RunStatus.CANCELLED;
      default -> throw new IllegalArgumentException("Unknown action " + action);
    }));
  }
  @GetMapping("/acquisitions/{id}/artifact")
  public ResponseEntity<byte[]> artifact(@PathVariable UUID crateId, @PathVariable UUID id)
      throws Exception {
    access.require(crateId, CrateMember.Role.VIEWER);
    AcquisitionRecord acquisition = acquisitions.findById(id).orElseThrow();
    if (!crateId.equals(acquisition.getCrateId()))
      throw new org.springframework.security.access.AccessDeniedException(
          "Artifact belongs to another crate");
    if (acquisition.getArtifactKey() == null) return ResponseEntity.notFound().build();
    try (var input = artifacts.open(acquisition.getArtifactKey())) {
      MediaType type;
      try {
        type = MediaType.parseMediaType(acquisition.getContentType());
      } catch (Exception e) {
        type = MediaType.APPLICATION_OCTET_STREAM;
      }
      return ResponseEntity.ok().contentType(type)
          .header(HttpHeaders.CONTENT_DISPOSITION, "inline").body(input.readAllBytes());
    }
  }

  private IngestionRunResponse response(IngestionRun run) {
    return IngestionRunResponse.from(run, ingestion.sourceConfiguration(run),
        ingestion.jobConfiguration(run));
  }
}
