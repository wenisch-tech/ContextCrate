package tech.wenisch.contextcrate.web;

import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import tech.wenisch.contextcrate.repository.*;
import tech.wenisch.contextcrate.service.JobService;
import tech.wenisch.contextcrate.service.CrateAccessService;
import tech.wenisch.contextcrate.domain.CrateMember;
import tech.wenisch.contextcrate.storage.ArtifactStore;

@RestController
@RequestMapping("/api/v1/crates/{crateId}/runs")
public class RunApiController {
  private final JobService jobs;
  private final FrontierEntryRepository frontier;
  private final FetchRecordRepository fetches;
  private final ArtifactStore artifacts;
  private final CrateAccessService access;

  public RunApiController(
      JobService jobs,
      FrontierEntryRepository frontier,
      FetchRecordRepository fetches,
      ArtifactStore artifacts, CrateAccessService access) {
    this.jobs = jobs;
    this.frontier = frontier;
    this.fetches = fetches;
    this.artifacts = artifacts;
    this.access = access;
  }

  @GetMapping
  public List<tech.wenisch.contextcrate.domain.CrawlRun> runs(@PathVariable UUID crateId) {
    access.require(crateId, CrateMember.Role.VIEWER);
    return jobs.runs(crateId);
  }

  @GetMapping("/{id}")
  public tech.wenisch.contextcrate.domain.CrawlRun run(@PathVariable UUID crateId, @PathVariable UUID id) {
    access.require(crateId, CrateMember.Role.VIEWER);
    return jobs.requireRun(crateId, id);
  }

  @GetMapping("/{id}/frontier")
  public List<tech.wenisch.contextcrate.domain.FrontierEntry> frontier(@PathVariable UUID crateId, @PathVariable UUID id) {
    access.require(crateId, CrateMember.Role.VIEWER);
    jobs.requireRun(crateId, id);
    return frontier.findTop100ByRunIdOrderByDiscoveredAtDesc(id);
  }

  @GetMapping("/{id}/fetches")
  public List<tech.wenisch.contextcrate.domain.FetchRecord> fetches(@PathVariable UUID crateId, @PathVariable UUID id) {
    access.require(crateId, CrateMember.Role.VIEWER);
    jobs.requireRun(crateId, id);
    return fetches.findTop100ByRunIdOrderByFetchedAtDesc(id);
  }

  @GetMapping("/fetches/{id}/artifact")
  public ResponseEntity<byte[]> artifact(@PathVariable UUID crateId, @PathVariable UUID id) throws Exception {
    access.require(crateId, CrateMember.Role.VIEWER);
    var fetch = fetches.findById(id).orElseThrow();
    if (!crateId.equals(fetch.getCrateId())) throw new org.springframework.security.access.AccessDeniedException("Artifact belongs to another crate");
    if (fetch.getArtifactKey() == null) return ResponseEntity.notFound().build();
    try (var in = artifacts.open(fetch.getArtifactKey())) {
      MediaType type;
      try {
        type = MediaType.parseMediaType(fetch.getContentType());
      } catch (Exception e) {
        type = MediaType.APPLICATION_OCTET_STREAM;
      }
      return ResponseEntity.ok()
          .contentType(type)
          .header(HttpHeaders.CONTENT_DISPOSITION, "inline")
          .body(in.readAllBytes());
    }
  }
}
