package tech.wenisch.contextcrate.backup;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.time.Instant;
import java.util.*;
import java.util.zip.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tech.wenisch.contextcrate.answer.RagSettingsService;
import tech.wenisch.contextcrate.config.RuntimeProviderSettings;
import tech.wenisch.contextcrate.domain.*;
import tech.wenisch.contextcrate.queue.*;
import tech.wenisch.contextcrate.repository.*;
import tech.wenisch.contextcrate.service.*;
import tech.wenisch.contextcrate.storage.*;

@Service
public class CratePortableService {
  private static final int SCHEMA = 3;
  private final ObjectMapper mapper;
  private final CrateService crates;
  private final CrateAccessService access;
  private final SourceRepository sources;
  private final IngestionJobRepository jobs;
  private final IngestionRunRepository runs;
  private final SourceItemRepository items;
  private final AcquisitionRecordRepository acquisitions;
  private final NormalizedDocumentRepository documents;
  private final DocumentChunkRepository chunks;
  private final ExtractionRuleRepository rules;
  private final ExtractionResultRepository results;
  private final ArtifactStore artifacts;
  private final SourceConfigurationCodec sourceCodec;
  private final IngestionConfigurationCodec jobCodec;
  private final RagSettingsService rag;
  private final RuntimeProviderSettings providers;
  private final PipelineQueue queue;

  public CratePortableService(ObjectMapper mapper, CrateService crates, CrateAccessService access,
      SourceRepository sources, IngestionJobRepository jobs, IngestionRunRepository runs,
      SourceItemRepository items, AcquisitionRecordRepository acquisitions,
      NormalizedDocumentRepository documents, DocumentChunkRepository chunks,
      ExtractionRuleRepository rules, ExtractionResultRepository results,
      ArtifactStore artifacts, SourceConfigurationCodec sourceCodec,
      IngestionConfigurationCodec jobCodec, RagSettingsService rag,
      RuntimeProviderSettings providers, PipelineQueue queue) {
    this.mapper = mapper; this.crates = crates; this.access = access; this.sources = sources;
    this.jobs = jobs; this.runs = runs; this.items = items; this.acquisitions = acquisitions;
    this.documents = documents; this.chunks = chunks; this.rules = rules; this.results = results;
    this.artifacts = artifacts; this.sourceCodec = sourceCodec; this.jobCodec = jobCodec;
    this.rag = rag; this.providers = providers; this.queue = queue;
  }

  public void exportTo(UUID crateId, Path target, boolean includeArtifacts) throws Exception {
    Crate crate = crates.require(crateId, CrateMember.Role.OWNER);
    Map<String, byte[]> entries = new LinkedHashMap<>();
    ObjectNode data = mapper.createObjectNode();
    data.set("crate", mapper.valueToTree(Map.of("name", crate.getName(), "description",
        crate.getDescription() == null ? "" : crate.getDescription())));
    data.set("sources", mapper.valueToTree(sources.findByCrateIdOrderByCreatedAtDesc(crateId).stream()
        .map(source -> Map.of("id", source.getId(), "name", source.getName(),
            "description", source.getDescription() == null ? "" : source.getDescription(),
            "connectorType", source.getConnectorType(), "enabled", source.isEnabled(),
            "configurationJson", sourceCodec.write(sourceCodec
                .read(source.getConfigurationJson(), source.getConnectorType()).withoutSecrets())))
        .toList()));
    data.set("ingestionJobs", mapper.valueToTree(jobs.findByCrateIdOrderByCreatedAtDesc(crateId).stream()
        .map(this::sanitizedJob).toList()));
    data.set("ingestionRuns", mapper.valueToTree(runs.findByCrateId(crateId).stream()
        .map(run -> sanitizedRun(run)).toList()));
    data.set("sourceItems", mapper.valueToTree(items.findByCrateId(crateId)));
    data.set("acquisitions", mapper.valueToTree(acquisitions.findByCrateId(crateId)));
    data.set("documents", mapper.valueToTree(documents.findByCrateId(crateId)));
    data.set("chunks", mapper.valueToTree(chunks.findByCrateId(crateId)));
    data.set("rules", mapper.valueToTree(rules.findByCrateIdOrderByCreatedAtDesc(crateId)));
    data.set("results", mapper.valueToTree(results.findByCrateId(crateId)));
    data.set("rag", mapper.valueToTree(rag.current(crateId)));
    var embedding = providers.effectiveEmbedding(crateId);
    var reranking = providers.effectiveReranking(crateId);
    var answering = providers.effectiveAnswer(crateId);
    data.set("providers", mapper.valueToTree(new ProviderExport(
        embedding.enabled(), embedding.provider(), embedding.localModelId(),
        embedding.localRevision(), embedding.localDownloadUrl(),
        embedding.localCachePath().toString(),
        embedding.localModelPath() == null ? null : embedding.localModelPath().toString(),
        embedding.openaiBaseUrl(), embedding.openaiModel(), embedding.openaiDimensions(),
        embedding.openaiMaxInputCharacters(),
        reranking.enabled(), reranking.provider(), reranking.candidateLimit(), reranking.localModelId(),
        reranking.localRevision(), reranking.localDownloadUrl(), reranking.localCachePath().toString(),
        reranking.localModelPath() == null ? null : reranking.localModelPath().toString(),
        reranking.cohereBaseUrl(), reranking.cohereModel(), reranking.cohereMaxInputCharacters(),
        reranking.cohereTimeoutSeconds(),
        answering.enabled(), answering.baseUrl(), answering.model())));
    entries.put("data.json", mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(data));
    if (includeArtifacts) for (var record : acquisitions.findByCrateId(crateId))
      if (record.getArtifactKey() != null && artifacts.exists(record.getArtifactKey()))
        try (var input = artifacts.open(record.getArtifactKey())) {
          entries.put("artifacts/" + record.getId(), input.readAllBytes());
        }
    Map<String, String> checksums = new LinkedHashMap<>();
    entries.forEach((name, bytes) -> checksums.put(name, Hashing.sha256(bytes)));
    Manifest manifest = new Manifest(SCHEMA, Instant.now(), crate.getName(), includeArtifacts,
        checksums);
    try (var zip = new ZipOutputStream(Files.newOutputStream(target))) {
      write(zip, "manifest.json", mapper.writerWithDefaultPrettyPrinter()
          .writeValueAsBytes(manifest));
      for (var entry : entries.entrySet()) write(zip, entry.getKey(), entry.getValue());
    }
  }

  private Map<String, Object> sanitizedRun(IngestionRun run) {
    Source source = sources.findById(run.getSourceId()).orElseThrow();
    String sourceJson = sourceCodec.write(sourceCodec
        .read(run.getSourceConfigurationJson(), source.getConnectorType()).withoutSecrets());
    Map<String, Object> value = new LinkedHashMap<>();
    value.put("id", run.getId()); value.put("sourceId", run.getSourceId());
    value.put("ingestionJobId", run.getIngestionJobId()); value.put("status", run.getStatus());
    value.put("sourceConfigurationJson", sourceJson);
    value.put("jobConfigurationJson", jobCodec.write(jobCodec.read(run.getJobConfigurationJson(),
        source.getConnectorType()).withoutSecrets()));
    value.put("resolvedRevision", run.getResolvedRevision());
    return value;
  }

  private Map<String, Object> sanitizedJob(IngestionJob job) {
    Source source = sources.findById(job.getSourceId()).orElseThrow();
    IngestionConfiguration configuration = jobCodec.read(job.getConfigurationJson(),
        source.getConnectorType());
    boolean tokenConfigured = configuration.git() != null && configuration.git().token() != null
        && !configuration.git().token().isBlank();
    return Map.of("id", job.getId(), "sourceId", job.getSourceId(), "name", job.getName(),
        "enabled", job.isEnabled(), "tokenConfigured", tokenConfigured,
        "configurationJson", jobCodec.write(configuration.withoutSecrets()));
  }

  public Manifest validate(Path bundle) throws Exception { return readBundle(bundle).manifest(); }

  @Transactional
  public ImportResult importFrom(Path path) throws Exception {
    Bundle bundle = readBundle(path);
    JsonNode data = mapper.readTree(bundle.entries().get("data.json"));
    Crate crate = crates.create(text(data.path("crate"), "name") + " (imported)",
        nullable(data.path("crate"), "description"));
    UUID crateId = crate.getId();
    Map<UUID, UUID> sourceIds = new HashMap<>(), jobIds = new HashMap<>(), runIds = new HashMap<>(),
        itemIds = new HashMap<>(), acquisitionIds = new HashMap<>(), documentIds = new HashMap<>(),
        chunkIds = new HashMap<>(), ruleIds = new HashMap<>();

    if (bundle.manifest().schemaVersion() == 1) {
      importLegacySources(data, crateId, sourceIds, jobIds);
    } else {
      for (JsonNode node : data.path("sources")) {
        UUID id = UUID.randomUUID(); sourceIds.put(uuid(node), id);
        ConnectorType type = ConnectorType.valueOf(text(node, "connectorType"));
        Source value = new Source(id, crateId, text(node, "name"), nullable(node, "description"), type,
            text(node, "configurationJson"));
        value.update(value.getName(), value.getDescription(), value.getConfigurationJson(),
            node.path("enabled").asBoolean());
        sources.save(value);
      }
      for (JsonNode node : data.path("ingestionJobs")) {
        UUID id = UUID.randomUUID(); jobIds.put(uuid(node), id);
        Source source = sources.findById(
            sourceIds.get(UUID.fromString(text(node, "sourceId")))).orElseThrow();
        IngestionJob value = new IngestionJob(id, crateId,
            source.getId(), text(node, "name"),
            text(node, "configurationJson"));
        boolean missingCredential = source.getConnectorType() == ConnectorType.GIT
            && node.path("tokenConfigured").asBoolean(false);
        value.update(value.getName(), value.getConfigurationJson(),
            node.path("enabled").asBoolean() && source.isEnabled() && !missingCredential);
        jobs.save(value);
      }
    }

    JsonNode runNodes = bundle.manifest().schemaVersion() == 1
        ? data.path("runs") : data.path("ingestionRuns");
    for (JsonNode node : runNodes) {
      UUID oldId = uuid(node), id = UUID.randomUUID(); runIds.put(oldId, id);
      UUID oldJobId = UUID.fromString(text(node,
          bundle.manifest().schemaVersion() == 1 ? "jobId" : "ingestionJobId"));
      UUID oldSourceId = bundle.manifest().schemaVersion() == 1 ? oldJobId
          : UUID.fromString(text(node, "sourceId"));
      IngestionJob job = jobs.findById(jobIds.get(oldJobId)).orElseThrow();
      Source source = sources.findById(sourceIds.get(oldSourceId)).orElseThrow();
      String jobJson = bundle.manifest().schemaVersion() == 1
          ? text(node, "configurationJson") : text(node, "jobConfigurationJson");
      String sourceJson = bundle.manifest().schemaVersion() == 1
          ? source.getConfigurationJson() : text(node, "sourceConfigurationJson");
      IngestionRun value = new IngestionRun(id, crateId, source.getId(), job.getId(),
          sourceJson, jobJson);
      value.status(PipelineTypes.RunStatus.valueOf(text(node, "status")));
      String revision = nullable(node, "resolvedRevision");
      if (revision != null) value.resolvedRevision(revision);
      runs.save(value);
    }

    importItemsAndAcquisitions(bundle, data, crateId, runIds, itemIds, acquisitionIds);
    for (JsonNode node : data.path("documents")) {
      UUID id = UUID.randomUUID(); documentIds.put(uuid(node), id);
      String acquisitionField = bundle.manifest().schemaVersion() == 1 ? "fetchId" : "acquisitionId";
      String uriField = bundle.manifest().schemaVersion() == 1 ? "canonicalUrl" : "sourceUri";
      NormalizedDocument value = new NormalizedDocument(id,
          runIds.get(UUID.fromString(text(node, "runId"))),
          acquisitionIds.get(UUID.fromString(text(node, acquisitionField))), text(node, uriField),
          nullable(node, "title"), nullable(node, "language"), nullable(node, "description"),
          nullable(node, "author"), text(node, "body"), text(node, "contentHash"),
          text(node, "metadataJson"));
      value.assignCrate(crateId); documents.save(value);
    }
    for (JsonNode node : data.path("chunks")) {
      UUID id = UUID.randomUUID(); chunkIds.put(uuid(node), id);
      DocumentChunk value = new DocumentChunk(id,
          documentIds.get(UUID.fromString(text(node, "documentId"))),
          node.path("ordinal").asInt(), nullable(node, "heading"), text(node, "content"),
          text(node, "contentHash"));
      value.assignCrate(crateId); chunks.save(value);
    }
    for (JsonNode node : data.path("rules")) {
      UUID id = UUID.randomUUID(); ruleIds.put(uuid(node), id);
      ExtractionRule value = new ExtractionRule(id, text(node, "name"),
          PipelineTypes.ExtractionType.valueOf(text(node, "type")), nullable(node, "pattern"),
          node.path("enabled").asBoolean());
      value.assignCrate(crateId); rules.save(value);
    }
    for (JsonNode node : data.path("results")) {
      ExtractionResult value = new ExtractionResult(UUID.randomUUID(),
          ruleIds.get(UUID.fromString(text(node, "ruleId"))),
          runIds.get(UUID.fromString(text(node, "runId"))),
          documentIds.get(UUID.fromString(text(node, "documentId"))),
          chunkIds.get(UUID.fromString(text(node, "chunkId"))), node.path("chunkOrdinal").asInt(),
          text(node, "matchedValue"), node.path("matchStart").asInt(),
          node.path("matchEnd").asInt(), nullable(node, "contextBefore"),
          nullable(node, "contextAfter"));
      value.assignCrate(crateId); results.save(value);
    }
    restoreSettings(crateId, data);
    for (NormalizedDocument document : documents.findByCrateId(crateId))
      queue.publish(PipelineMessage.create(crateId, PipelineTypes.WorkStage.INDEX,
          IngestionService.payload(crateId, document.getRunId(), document.getId()),
          document.getRunId(), crateId + ":import-index:" + document.getId(), 20));
    return new ImportResult(crateId, documents.findByCrateId(crateId).size());
  }

  private void importLegacySources(JsonNode data, UUID crateId, Map<UUID, UUID> sourceIds,
      Map<UUID, UUID> jobIds) {
    for (JsonNode node : data.path("jobs")) {
      UUID oldId = uuid(node), sourceId = UUID.randomUUID(), jobId = UUID.randomUUID();
      sourceIds.put(oldId, sourceId); jobIds.put(oldId, jobId);
      String legacy = text(node, "configurationJson");
      SourceConfiguration sourceConfig = sourceCodec.read(legacy, ConnectorType.HTTPS);
      Source source = new Source(sourceId, crateId, text(node, "name"),
          null, ConnectorType.HTTPS, sourceCodec.write(sourceConfig));
      source.update(source.getName(), source.getDescription(), source.getConfigurationJson(),
          node.path("enabled").asBoolean());
      sources.save(source);
      IngestionConfiguration config = jobCodec.read(legacy, ConnectorType.HTTPS);
      IngestionJob job = new IngestionJob(jobId, crateId, sourceId, text(node, "name"),
          jobCodec.write(config));
      job.update(job.getName(), job.getConfigurationJson(), node.path("enabled").asBoolean());
      jobs.save(job);
    }
  }

  private void importItemsAndAcquisitions(Bundle bundle, JsonNode data, UUID crateId,
      Map<UUID, UUID> runIds, Map<UUID, UUID> itemIds, Map<UUID, UUID> acquisitionIds)
      throws Exception {
    boolean legacy = bundle.manifest().schemaVersion() == 1;
    for (JsonNode node : data.path(legacy ? "frontier" : "sourceItems")) {
      UUID id = UUID.randomUUID(); itemIds.put(uuid(node), id);
      SourceItem value = new SourceItem(id, runIds.get(UUID.fromString(text(node, "runId"))),
          text(node, legacy ? "url" : "locator"),
          text(node, legacy ? "canonicalUrl" : "sourceUri"), node.path("depth").asInt());
      value.assignCrate(crateId);
      value.status(PipelineTypes.FrontierStatus.valueOf(text(node, "status")));
      items.save(value);
    }
    for (JsonNode node : data.path(legacy ? "fetches" : "acquisitions")) {
      UUID oldId = uuid(node), id = UUID.randomUUID(); acquisitionIds.put(oldId, id);
      UUID runId = runIds.get(UUID.fromString(text(node, "runId")));
      AcquisitionRecord value = new AcquisitionRecord(id, runId,
          itemIds.get(UUID.fromString(text(node, legacy ? "frontierEntryId" : "sourceItemId"))),
          text(node, legacy ? "requestedUrl" : "requestedLocator"));
      value.assignCrate(crateId);
      byte[] body = bundle.entries().get("artifacts/" + oldId);
      if (body != null) {
        String key = "crates/" + crateId + "/runs/" + runId + "/" + id + ".imported";
        var saved = artifacts.put(key, new ByteArrayInputStream(body), body.length + 1L);
        value.success(nullable(node, legacy ? "finalUrl" : "finalLocator"),
            node.path("statusCode").asInt(), nullable(node, "contentType"),
            nullable(node, "charset"), saved.key(), saved.sha256(), saved.length(),
            node.path("durationMs").asLong());
      } else value.failure(PipelineTypes.FetchOutcome.valueOf(text(node, "outcome")),
          nullable(node, "errorMessage"));
      acquisitions.save(value);
    }
  }

  private void restoreSettings(UUID crateId, JsonNode data) {
    JsonNode r = data.path("rag");
    rag.update(crateId, r.path("strictGrounding").asBoolean(),
        r.path("allowClientHistory").asBoolean(), r.path("inlineCitations").asBoolean(),
        r.path("structuredSources").asBoolean(), text(r, "retrievalMode"),
        r.path("sourceLimit").asInt());
    JsonNode p = data.path("providers");
    providers.update(crateId, new RuntimeProviderSettings.ProviderForm(
        p.path("embeddingsEnabled").asBoolean(), text(p, "embeddingProvider"),
        nullable(p, "localModelId"), nullable(p, "localRevision"), nullable(p, "localDownloadUrl"),
        nullable(p, "localCachePath"), nullable(p, "localModelPath"),
        nullable(p, "openaiBaseUrl"), nullable(p, "openaiModel"), null,
        p.path("openaiDimensions").asInt(1536), p.path("openaiMaxInputCharacters").asInt(8000), p.path("rerankingEnabled").asBoolean(false), textOr(p,"rerankingProvider","local"), p.path("rerankingCandidateLimit").asInt(30), nullable(p,"rerankingLocalModelId"), nullable(p,"rerankingLocalRevision"), nullable(p,"rerankingLocalDownloadUrl"), nullable(p,"rerankingLocalCachePath"), nullable(p,"rerankingLocalModelPath"), nullable(p,"rerankingCohereBaseUrl"), nullable(p,"rerankingCohereModel"), null, p.path("rerankingCohereMaxInputCharacters").asInt(4000), p.path("rerankingCohereTimeoutSeconds").asInt(30), p.path("answeringEnabled").asBoolean(),
        nullable(p, "answeringBaseUrl"), nullable(p, "answeringModel"), null));
  }

  private Bundle readBundle(Path path) throws Exception {
    Map<String, byte[]> entries = new LinkedHashMap<>();
    try (var zip = new ZipInputStream(Files.newInputStream(path))) {
      ZipEntry entry;
      while ((entry = zip.getNextEntry()) != null) {
        if (entry.isDirectory()) continue;
        String name = entry.getName();
        if (name.startsWith("/") || name.contains("..")) throw new IOException("Unsafe bundle entry");
        entries.put(name, zip.readAllBytes());
      }
    }
    Manifest manifest = mapper.readValue(required(entries, "manifest.json"), Manifest.class);
    if (manifest.schemaVersion() != 1 && manifest.schemaVersion() != 2 && manifest.schemaVersion() != SCHEMA)
      throw new IOException("Unsupported crate export schema");
    for (var checksum : manifest.checksums().entrySet())
      if (!Hashing.sha256(required(entries, checksum.getKey())).equals(checksum.getValue()))
        throw new IOException("Checksum mismatch: " + checksum.getKey());
    return new Bundle(manifest, entries);
  }

  private static byte[] required(Map<String, byte[]> values, String key) throws IOException {
    byte[] value = values.get(key);
    if (value == null) throw new IOException("Missing " + key);
    return value;
  }
  private static void write(ZipOutputStream zip, String name, byte[] value) throws IOException {
    zip.putNextEntry(new ZipEntry(name)); zip.write(value); zip.closeEntry();
  }
  private static UUID uuid(JsonNode node) { return UUID.fromString(text(node, "id")); }
  private static String text(JsonNode node, String field) { return node.path(field).asText(); }
  private static String nullable(JsonNode node, String field) {
    return node.hasNonNull(field) && !node.path(field).asText().isBlank()
        ? node.path(field).asText() : null;
  }
  private static String textOr(JsonNode node,String field,String fallback){String value=text(node,field);return value.isBlank()?fallback:value;}

  public record Manifest(int schemaVersion, Instant createdAt, String crateName,
      boolean includesArtifacts, Map<String, String> checksums) {}
  private record Bundle(Manifest manifest, Map<String, byte[]> entries) {}
  public record ImportResult(UUID crateId, long documents) {}
  public record ProviderExport(boolean embeddingsEnabled, String embeddingProvider,
      String localModelId, String localRevision, String localDownloadUrl, String localCachePath,
      String localModelPath, String openaiBaseUrl, String openaiModel, int openaiDimensions,
      int openaiMaxInputCharacters, boolean rerankingEnabled, String rerankingProvider,
      int rerankingCandidateLimit, String rerankingLocalModelId, String rerankingLocalRevision,
      String rerankingLocalDownloadUrl, String rerankingLocalCachePath, String rerankingLocalModelPath,
      String rerankingCohereBaseUrl, String rerankingCohereModel, int rerankingCohereMaxInputCharacters,
      int rerankingCohereTimeoutSeconds, boolean answeringEnabled, String answeringBaseUrl,
      String answeringModel) {}
}
