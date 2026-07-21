package tech.wenisch.harvex.web;

import static tech.wenisch.harvex.domain.PipelineTypes.WorkStage;

import java.util.*;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import tech.wenisch.harvex.config.HarvexProperties;
import tech.wenisch.harvex.domain.CrawlConfiguration;
import tech.wenisch.harvex.domain.PipelineTypes.ExtractionType;
import tech.wenisch.harvex.index.SearchIndex;
import tech.wenisch.harvex.queue.PipelineQueue;
import tech.wenisch.harvex.repository.*;
import tech.wenisch.harvex.service.*;

@Controller
public class UiController {
  private final JobService jobs;
  private final ConfigurationCodec codec;
  private final HarvexProperties properties;
  private final PipelineQueue queue;
  private final SearchIndex index;
  private final NormalizedDocumentRepository documents;
  private final ExtractionService extraction;

  public UiController(
      JobService jobs,
      ConfigurationCodec codec,
      HarvexProperties properties,
      PipelineQueue queue,
      SearchIndex index,
      NormalizedDocumentRepository documents,
      ExtractionService extraction) {
    this.jobs = jobs;
    this.codec = codec;
    this.properties = properties;
    this.queue = queue;
    this.index = index;
    this.documents = documents;
    this.extraction = extraction;
  }

  @GetMapping("/login")
  String login() {
    return "login";
  }

  @GetMapping("/")
  String dashboard(@RequestParam(defaultValue = "") String q, Model model) throws Exception {
    model.addAttribute("jobs", jobs.jobs());
    model.addAttribute("runs", jobs.runs());
    model.addAttribute("properties", properties);
    model.addAttribute("indexHealth", index.health());
    model.addAttribute(
        "queueDepth",
        Arrays.stream(WorkStage.values())
            .collect(java.util.stream.Collectors.toMap(Enum::name, queue::depth)));
    model.addAttribute("documentCount", documents.count());
        model.addAttribute("searchQuery", q);
        model.addAttribute(
          "searchResults",
          q.isBlank() ? null : index.search(new SearchIndex.SearchRequest(q, 10, null, null, null)));
    return "dashboard";
  }

  @GetMapping("/jobs")
  String jobList(Model model) {
    model.addAttribute("jobs", jobs.jobs());
    model.addAttribute("codec", codec);
    return "jobs";
  }

  @GetMapping("/jobs/new")
  String newJob() {
    return "job-form";
  }

  @PostMapping("/jobs")
  String create(
      @RequestParam String name,
      @RequestParam String seedUrl,
      @RequestParam String allowedHost,
      @RequestParam int maxDepth,
      @RequestParam int maxPages,
      @RequestParam(defaultValue = "false") boolean allowSubdomains,
      @RequestParam(defaultValue = "HarvexBot/0.1") String userAgent,
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
      @RequestParam(defaultValue = "200") int chunkOverlap) {
    var c =
        new CrawlConfiguration(
            new CrawlConfiguration.Scope(
                seedUrl,
                Set.of(allowedHost),
                List.of(),
                List.of(),
                maxDepth,
                maxPages,
                allowSubdomains,
                true),
            new CrawlConfiguration.Politeness(
                userAgent, contact, honorRobots, 1, delayMillis, timeoutMillis),
            new CrawlConfiguration.Reliability(
                maxAttempts, backoffMillis, maxBodyMegabytes * 1_000_000, true, renderMode),
            new CrawlConfiguration.Output(
                retentionDays,
                contentSelector,
                List.of("script", "style", "nav", "footer", "aside"),
                chunkSize,
                chunkOverlap,
                "default"));
    jobs.create(name, c);
    return "redirect:/jobs";
  }

  @PostMapping("/jobs/{id}/start")
  String start(@PathVariable UUID id) {
    jobs.start(id);
    return "redirect:/";
  }

  @GetMapping("/documents")
  String docs(Model model) {
    model.addAttribute("documents", documents.findTop100ByOrderByCreatedAtDesc());
    return "documents";
  }

  @GetMapping("/extractions")
  String extractions(Model model) {
    model.addAttribute("rules", extraction.rules());
    model.addAttribute("types", ExtractionType.values());
    model.addAttribute("runs", jobs.runs());
    model.addAttribute(
        "results", extraction.search(null, null, null, null, null, PageRequest.of(0, 50)));
    model.addAttribute("extraction", extraction);
    return "extractions";
  }

  @PostMapping("/extractions/rules")
  String createExtractionRule(
      @RequestParam String name,
      @RequestParam ExtractionType type,
      @RequestParam(defaultValue = "") String pattern,
      @RequestParam(defaultValue = "false") boolean enabled) {
    extraction.createRule(name, type, pattern, enabled);
    return "redirect:/extractions";
  }

  @PostMapping("/extractions/rules/{id}/disable")
  String disableExtractionRule(@PathVariable UUID id) {
    extraction.deleteRule(id);
    return "redirect:/extractions";
  }

  @PostMapping("/extractions/runs/{id}/rebuild")
  String rebuildRunExtractions(@PathVariable UUID id) {
    extraction.rebuildRun(id);
    return "redirect:/extractions";
  }

  @GetMapping("/operations")
  String operations(Model model) {
    model.addAttribute("properties", properties);
    model.addAttribute("indexHealth", index.health());
    model.addAttribute("deadLetters", queue.deadLetters());
    model.addAttribute(
        "queueDepth",
        Arrays.stream(WorkStage.values())
            .collect(java.util.stream.Collectors.toMap(Enum::name, queue::depth)));
    return "operations";
  }
}
