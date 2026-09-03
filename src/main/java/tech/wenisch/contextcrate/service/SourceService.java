package tech.wenisch.contextcrate.service;

import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tech.wenisch.contextcrate.domain.*;
import tech.wenisch.contextcrate.repository.*;

@Service
public class SourceService {
  private final SourceRepository sources;
  private final IngestionJobRepository jobs;
  private final NormalizedDocumentRepository documents;
  private final IngestionRunRepository runs;
  private final SourceConfigurationCodec codec;
  private final AuditLogRepository audits;

  public SourceService(SourceRepository sources, IngestionJobRepository jobs,
      NormalizedDocumentRepository documents, IngestionRunRepository runs,
      SourceConfigurationCodec codec, AuditLogRepository audits) {
    this.sources = sources;
    this.jobs = jobs;
    this.documents = documents;
    this.runs = runs;
    this.codec = codec;
    this.audits = audits;
  }

  @Transactional
  public Source create(UUID crateId, String name, String description, ConnectorType type,
      SourceConfiguration config) {
    validate(type, config);
    Source value = sources.save(new Source(UUID.randomUUID(), crateId, name, description, type,
        codec.write(config)));
    audits.save(new AuditLog(crateId, actor(), "SOURCE_CREATED", value.getId().toString(),
        "Created " + type + " source " + name));
    return value;
  }

  @Transactional
  public Source update(UUID crateId, UUID id, String name, String description,
      SourceConfiguration submitted,
      boolean enabled) {
    Source source = require(crateId, id);
    validate(source.getConnectorType(), submitted);
    source.update(name, description, codec.write(submitted), enabled);
    return sources.save(source);
  }

  public List<Source> list(UUID crateId) {
    return sources.findByCrateIdOrderByCreatedAtDesc(crateId);
  }

  public Source require(UUID crateId, UUID id) {
    return sources.findByIdAndCrateId(id, crateId).orElseThrow();
  }

  public long jobCount(UUID sourceId) {
    return jobs.countBySourceId(sourceId);
  }

  @Transactional(readOnly = true)
  public List<SourceSummary> summaries(UUID crateId) {
    List<Source> values = list(crateId);
    if (values.isEmpty()) return List.of();
    List<UUID> ids = values.stream().map(Source::getId).toList();
    Map<UUID, Long> jobCounts = jobs.countBySource(crateId, ids).stream()
        .collect(java.util.stream.Collectors.toMap(IngestionJobRepository.SourceJobCount::getSourceId,
            IngestionJobRepository.SourceJobCount::getJobs));
    Map<UUID, NormalizedDocumentRepository.SourceContentCount> content = documents
        .countCurrentContentBySource(crateId, ids).stream().collect(java.util.stream.Collectors.toMap(
            NormalizedDocumentRepository.SourceContentCount::getSourceId, value -> value));
    Map<UUID, IngestionRun> latestRuns = runs.findLatestBySourceIdIn(crateId, ids).stream()
        .collect(java.util.stream.Collectors.toMap(IngestionRun::getSourceId, value -> value,
            (first, ignored) -> first));
    return values.stream().map(source -> {
      var counts = content.get(source.getId());
      return new SourceSummary(source, jobCounts.getOrDefault(source.getId(), 0L),
          counts == null ? 0 : counts.getDocuments(), counts == null ? 0 : counts.getChunks(),
          latestRuns.get(source.getId()));
    }).toList();
  }

  public record SourceSummary(Source source, long jobs, long documents, long chunks,
      IngestionRun latestRun) {}

  private static void validate(ConnectorType type, SourceConfiguration config) {
    if (config == null) throw new IllegalArgumentException("configuration is required");
    if (type == ConnectorType.GIT) {
      if (config.git() == null) throw new IllegalArgumentException("git configuration is required");
    } else {
      if (config.website() == null)
        throw new IllegalArgumentException("HTTPS configuration is required");
    }
  }

  private static String actor() {
    var authentication = org.springframework.security.core.context.SecurityContextHolder.getContext()
        .getAuthentication();
    return authentication == null ? "system" : authentication.getName();
  }
}
