package tech.wenisch.harvex.backup;

import static tech.wenisch.harvex.domain.PipelineTypes.*;

import com.fasterxml.jackson.databind.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.time.Instant;
import java.util.*;
import java.util.function.Consumer;
import java.util.zip.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tech.wenisch.harvex.config.HarvexProperties;
import tech.wenisch.harvex.domain.*;
import tech.wenisch.harvex.queue.*;
import tech.wenisch.harvex.repository.*;
import tech.wenisch.harvex.storage.*;

@Service
public class PortableBackupService {
  private static final int SCHEMA = 1;
  private final ObjectMapper mapper;
  private final HarvexProperties properties;
  private final ArtifactStore artifacts;
  private final PipelineQueue queue;
  private final CrawlJobRepository jobs;
  private final CrawlRunRepository runs;
  private final FrontierEntryRepository frontier;
  private final FetchRecordRepository fetches;
  private final NormalizedDocumentRepository documents;
  private final DocumentChunkRepository chunks;
  private final PipelineWorkItemRepository work;
  private final AuditLogRepository audits;

  public PortableBackupService(
      ObjectMapper mapper,
      HarvexProperties properties,
      ArtifactStore artifacts,
      PipelineQueue queue,
      CrawlJobRepository jobs,
      CrawlRunRepository runs,
      FrontierEntryRepository frontier,
      FetchRecordRepository fetches,
      NormalizedDocumentRepository documents,
      DocumentChunkRepository chunks,
      PipelineWorkItemRepository work,
      AuditLogRepository audits) {
    this.mapper = mapper;
    this.properties = properties;
    this.artifacts = artifacts;
    this.queue = queue;
    this.jobs = jobs;
    this.runs = runs;
    this.frontier = frontier;
    this.fetches = fetches;
    this.documents = documents;
    this.chunks = chunks;
    this.work = work;
    this.audits = audits;
  }

  public Path create(Path destination, boolean includeArtifacts) throws Exception {
    Path absolute = destination.toAbsolutePath().normalize();
    if (absolute.getParent() != null) Files.createDirectories(absolute.getParent());
    Map<String, String> checksums = new LinkedHashMap<>();
    Map<String, Long> counts = new LinkedHashMap<>();
    try (var output = Files.newOutputStream(absolute);
        var zip = new ZipOutputStream(new BufferedOutputStream(output))) {
      writeJsonl(zip, "data/jobs.jsonl", jobs.findAll(), checksums, counts);
      writeJsonl(zip, "data/runs.jsonl", runs.findAll(), checksums, counts);
      writeJsonl(zip, "data/frontier.jsonl", frontier.findAll(), checksums, counts);
      writeJsonl(zip, "data/fetches.jsonl", fetches.findAll(), checksums, counts);
      writeJsonl(zip, "data/documents.jsonl", documents.findAll(), checksums, counts);
      writeJsonl(zip, "data/chunks.jsonl", chunks.findAll(), checksums, counts);
      writeJsonl(
          zip,
          "data/work-items.jsonl",
          work.findAll().stream().filter(w -> w.getStatus() != WorkStatus.COMPLETED).toList(),
          checksums,
          counts);
      writeJsonl(zip, "data/audit.jsonl", audits.findAll(), checksums, counts);
      if (includeArtifacts) {
        Set<String> seen = new HashSet<>();
        for (FetchRecord fetch : fetches.findAll())
          if (fetch.getArtifactKey() != null
              && seen.add(fetch.getArtifactKey())
              && artifacts.exists(fetch.getArtifactKey()))
            writeArtifact(zip, fetch.getArtifactKey(), checksums);
      }
      var manifest =
          new Manifest(
              SCHEMA,
              Instant.now(),
              properties.profile(),
              Map.of(
                  "queue",
                  properties.queue().backend(),
                  "database",
                  properties.database().backend(),
                  "artifacts",
                  properties.artifacts().backend(),
                  "index",
                  properties.index().backend()),
              includeArtifacts,
              counts,
              checksums);
      byte[] bytes = mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(manifest);
      zip.putNextEntry(new ZipEntry("manifest.json"));
      zip.write(bytes);
      zip.closeEntry();
    }
    return absolute;
  }

  public Manifest validate(Path bundle) throws Exception {
    Path temp = extractAndVerify(bundle);
    try {
      return mapper.readValue(temp.resolve("manifest.json").toFile(), Manifest.class);
    } finally {
      deleteTree(temp);
    }
  }

  @Transactional
  public RestoreResult restore(Path bundle) throws Exception {
    if (jobs.count()
            + runs.count()
            + frontier.count()
            + fetches.count()
            + documents.count()
            + chunks.count()
            + audits.count()
        > 0) throw new IllegalStateException("Restore target is not empty");
    Path temp = extractAndVerify(bundle);
    try {
      Manifest manifest = mapper.readValue(temp.resolve("manifest.json").toFile(), Manifest.class);
      readLines(
          temp,
          "data/jobs.jsonl",
          n -> {
            var j = new CrawlJob(uuid(n), text(n, "name"), text(n, "configurationJson"));
            j.update(text(n, "name"), text(n, "configurationJson"), bool(n, "enabled"));
            jobs.save(j);
          });
      readLines(
          temp,
          "data/runs.jsonl",
          n -> {
            var r =
                new CrawlRun(
                    uuid(n), UUID.fromString(text(n, "jobId")), text(n, "configurationJson"));
            r.status(RunStatus.valueOf(text(n, "status")));
            runs.save(r);
          });
      readLines(
          temp,
          "data/frontier.jsonl",
          n -> {
            var e =
                new FrontierEntry(
                    uuid(n),
                    UUID.fromString(text(n, "runId")),
                    text(n, "url"),
                    text(n, "canonicalUrl"),
                    n.path("depth").asInt());
            e.status(FrontierStatus.valueOf(text(n, "status")));
            frontier.save(e);
          });
      readLines(
          temp,
          "data/fetches.jsonl",
          n -> {
            var f =
                new FetchRecord(
                    uuid(n),
                    UUID.fromString(text(n, "runId")),
                    UUID.fromString(text(n, "frontierEntryId")),
                    text(n, "requestedUrl"));
            if (!n.path("artifactKey").isNull() && n.hasNonNull("artifactKey"))
              f.success(
                  text(n, "finalUrl"),
                  n.path("statusCode").asInt(),
                  text(n, "contentType"),
                  text(n, "charset"),
                  text(n, "artifactKey"),
                  text(n, "artifactSha256"),
                  n.path("artifactLength").asLong(),
                  n.path("durationMs").asLong());
            else f.failure(FetchOutcome.valueOf(text(n, "outcome")), text(n, "errorMessage"));
            fetches.save(f);
          });
      readLines(
          temp,
          "data/documents.jsonl",
          n -> {
            var d =
                new NormalizedDocument(
                    uuid(n),
                    UUID.fromString(text(n, "runId")),
                    UUID.fromString(text(n, "fetchId")),
                    text(n, "canonicalUrl"),
                    nullable(n, "title"),
                    nullable(n, "language"),
                    nullable(n, "description"),
                    nullable(n, "author"),
                    text(n, "body"),
                    text(n, "contentHash"),
                    text(n, "metadataJson"));
            documents.save(d);
          });
      readLines(
          temp,
          "data/chunks.jsonl",
          n ->
              chunks.save(
                  new DocumentChunk(
                      uuid(n),
                      UUID.fromString(text(n, "documentId")),
                      n.path("ordinal").asInt(),
                      nullable(n, "heading"),
                      text(n, "content"),
                      text(n, "contentHash"))));
      Path artifactRoot = temp.resolve("artifacts");
      if (Files.isDirectory(artifactRoot))
        try (var paths = Files.walk(artifactRoot)) {
          for (Path p : paths.filter(Files::isRegularFile).toList()) {
            String key = artifactRoot.relativize(p).toString().replace('\\', '/');
            try (var in = Files.newInputStream(p)) {
              artifacts.put(key, in, Files.size(p) + 1);
            }
          }
        }
      readLines(
          temp,
          "data/audit.jsonl",
          n ->
              audits.save(
                  new AuditLog(
                      text(n, "actor"),
                      text(n, "action"),
                      text(n, "subject"),
                      text(n, "details"))));
      readLines(
          temp,
          "data/work-items.jsonl",
          n -> {
            WorkStage stage = WorkStage.valueOf(text(n, "stage"));
            queue.publish(
                new PipelineMessage(
                    uuid(n),
                    n.path("schemaVersion").asInt(1),
                    stage,
                    text(n, "payload"),
                    UUID.fromString(text(n, "correlationId")),
                    text(n, "idempotencyKey"),
                    n.path("priority").asInt(),
                    0,
                    Instant.now()));
          });
      for (NormalizedDocument d : documents.findAll())
        queue.publish(
            PipelineMessage.create(
                WorkStage.INDEX,
                "{\"runId\":\"" + d.getRunId() + "\",\"entityId\":\"" + d.getId() + "\"}",
                d.getRunId(),
                "restore-index:" + d.getId(),
                20));
      return new RestoreResult(manifest, jobs.count(), documents.count());
    } finally {
      deleteTree(temp);
    }
  }

  private void writeJsonl(
      ZipOutputStream zip,
      String name,
      List<?> rows,
      Map<String, String> checksums,
      Map<String, Long> counts)
      throws Exception {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    for (Object row : rows) {
      mapper.writeValue(bytes, row);
      bytes.write('\n');
    }
    writeBytes(zip, name, bytes.toByteArray(), checksums);
    counts.put(name, (long) rows.size());
  }

  private void writeArtifact(ZipOutputStream zip, String key, Map<String, String> checksums)
      throws Exception {
    String name = "artifacts/" + safeKey(key);
    MessageDigest digest = digest();
    zip.putNextEntry(new ZipEntry(name));
    try (var in = new DigestInputStream(artifacts.open(key), digest)) {
      in.transferTo(zip);
    }
    zip.closeEntry();
    checksums.put(name, HexFormat.of().formatHex(digest.digest()));
  }

  private static void writeBytes(
      ZipOutputStream zip, String name, byte[] bytes, Map<String, String> checksums)
      throws IOException {
    zip.putNextEntry(new ZipEntry(name));
    zip.write(bytes);
    zip.closeEntry();
    checksums.put(name, Hashing.sha256(bytes));
  }

  private Path extractAndVerify(Path bundle) throws Exception {
    Path temp = Files.createTempDirectory("harvex-restore-");
    try (var zip = new ZipInputStream(Files.newInputStream(bundle))) {
      ZipEntry entry;
      while ((entry = zip.getNextEntry()) != null) {
        Path target = temp.resolve(entry.getName()).normalize();
        if (!target.startsWith(temp))
          throw new IOException("Unsafe backup entry: " + entry.getName());
        if (entry.isDirectory()) Files.createDirectories(target);
        else {
          Files.createDirectories(target.getParent());
          try (var out = Files.newOutputStream(target)) {
            zip.transferTo(out);
          }
        }
        zip.closeEntry();
      }
    }
    Path manifestPath = temp.resolve("manifest.json");
    if (!Files.isRegularFile(manifestPath)) throw new IOException("Backup has no manifest");
    Manifest manifest = mapper.readValue(manifestPath.toFile(), Manifest.class);
    if (manifest.schemaVersion() != SCHEMA)
      throw new IOException("Unsupported backup schema " + manifest.schemaVersion());
    for (var expected : manifest.checksums().entrySet()) {
      Path path = temp.resolve(expected.getKey()).normalize();
      if (!path.startsWith(temp)
          || !Files.isRegularFile(path)
          || !hash(path).equals(expected.getValue()))
        throw new IOException("Backup checksum mismatch: " + expected.getKey());
    }
    return temp;
  }

  private void readLines(Path root, String name, Consumer<JsonNode> consumer) throws IOException {
    Path file = root.resolve(name);
    if (!Files.exists(file)) return;
    try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
      String line;
      while ((line = reader.readLine()) != null)
        if (!line.isBlank()) consumer.accept(mapper.readTree(line));
    }
  }

  private static UUID uuid(JsonNode n) {
    return UUID.fromString(text(n, "id"));
  }

  private static String text(JsonNode n, String field) {
    return n.path(field).asText();
  }

  private static String nullable(JsonNode n, String field) {
    return n.hasNonNull(field) ? n.get(field).asText() : null;
  }

  private static boolean bool(JsonNode n, String field) {
    return n.path(field).asBoolean();
  }

  private static String safeKey(String key) {
    Path normalized = Path.of(key.replace('\\', '/')).normalize();
    if (normalized.isAbsolute() || normalized.startsWith(".."))
      throw new IllegalArgumentException("Unsafe artifact key");
    return normalized.toString().replace('\\', '/');
  }

  private static MessageDigest digest() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private static String hash(Path path) throws IOException {
    MessageDigest digest = digest();
    try (var in = new DigestInputStream(Files.newInputStream(path), digest)) {
      in.transferTo(OutputStream.nullOutputStream());
    }
    return HexFormat.of().formatHex(digest.digest());
  }

  private static void deleteTree(Path root) throws IOException {
    if (root == null || !Files.exists(root)) return;
    try (var paths = Files.walk(root)) {
      for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
    }
  }

  public record Manifest(
      int schemaVersion,
      Instant createdAt,
      String sourceProfile,
      Map<String, String> sourceBackends,
      boolean includesArtifacts,
      Map<String, Long> counts,
      Map<String, String> checksums) {}

  public record RestoreResult(Manifest manifest, long jobs, long documents) {}
}
