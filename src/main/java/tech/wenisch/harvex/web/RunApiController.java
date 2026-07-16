package tech.wenisch.harvex.web;

import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import tech.wenisch.harvex.repository.*;
import tech.wenisch.harvex.service.JobService;
import tech.wenisch.harvex.storage.ArtifactStore;

@RestController
@RequestMapping("/api/v1/runs")
public class RunApiController {
  private final JobService jobs;
  private final FrontierEntryRepository frontier;
  private final FetchRecordRepository fetches;
  private final ArtifactStore artifacts;

  public RunApiController(
      JobService jobs,
      FrontierEntryRepository frontier,
      FetchRecordRepository fetches,
      ArtifactStore artifacts) {
    this.jobs = jobs;
    this.frontier = frontier;
    this.fetches = fetches;
    this.artifacts = artifacts;
  }

  @GetMapping
  public List<tech.wenisch.harvex.domain.CrawlRun> runs() {
    return jobs.runs();
  }

  @GetMapping("/{id}")
  public tech.wenisch.harvex.domain.CrawlRun run(@PathVariable UUID id) {
    return jobs.requireRun(id);
  }

  @GetMapping("/{id}/frontier")
  public List<tech.wenisch.harvex.domain.FrontierEntry> frontier(@PathVariable UUID id) {
    jobs.requireRun(id);
    return frontier.findTop100ByRunIdOrderByDiscoveredAtDesc(id);
  }

  @GetMapping("/{id}/fetches")
  public List<tech.wenisch.harvex.domain.FetchRecord> fetches(@PathVariable UUID id) {
    jobs.requireRun(id);
    return fetches.findTop100ByRunIdOrderByFetchedAtDesc(id);
  }

  @GetMapping("/fetches/{id}/artifact")
  public ResponseEntity<byte[]> artifact(@PathVariable UUID id) throws Exception {
    var fetch = fetches.findById(id).orElseThrow();
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
