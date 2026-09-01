package tech.wenisch.contextcrate.git;

import static tech.wenisch.contextcrate.domain.PipelineTypes.*;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.*;
import java.util.regex.Pattern;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.transport.HttpTransport;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.eclipse.jgit.transport.http.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tech.wenisch.contextcrate.crawl.UrlPolicy;
import tech.wenisch.contextcrate.domain.*;
import tech.wenisch.contextcrate.queue.*;
import tech.wenisch.contextcrate.repository.*;
import tech.wenisch.contextcrate.service.*;
import tech.wenisch.contextcrate.storage.ArtifactStore;
import tech.wenisch.contextcrate.util.InsecureSsl;

@Service
public class GitSourceRetriever {
  private final SourceItemRepository items;
  private final AcquisitionRecordRepository acquisitions;
  private final IngestionRunRepository runs;
  private final ArtifactStore artifacts;
  private final PipelineQueue queue;
  private final IngestionService ingestion;
  private final UrlPolicy urls;

  public GitSourceRetriever(SourceItemRepository items,
      AcquisitionRecordRepository acquisitions, IngestionRunRepository runs,
      ArtifactStore artifacts, PipelineQueue queue, IngestionService ingestion, UrlPolicy urls) {
    this.items = items;
    this.acquisitions = acquisitions;
    this.runs = runs;
    this.artifacts = artifacts;
    this.queue = queue;
    this.ingestion = ingestion;
    this.urls = urls;
    installSafeHttpConnections(urls);
  }

  @Transactional
  public void fetch(PipelinePayload payload) throws Exception {
    SourceItem root = items.findById(payload.entityId()).orElseThrow();
    IngestionRun run = runs.findById(payload.runId()).orElseThrow();
    requireCrate(payload, run, root);
    if (run.getStatus() != RunStatus.RUNNING) return;

    SourceConfiguration.GitRepository source = ingestion.sourceConfiguration(run).git();
    IngestionConfiguration.Git config = ingestion.jobConfiguration(run).git();
    urls.assertSafe(source.repositoryUrl());

    Path checkout = Files.createTempDirectory("contextcrate-git-");
    long started = System.nanoTime();
    try {
      var clone = Git.cloneRepository()
          .setURI(source.repositoryUrl())
          .setDirectory(checkout.toFile())
          .setCloneAllBranches(true)
          .setTimeout(120);
      if (config.token() != null && !config.token().isBlank()) {
        clone.setCredentialsProvider(new UsernamePasswordCredentialsProvider(
            config.username() == null || config.username().isBlank() ? "git" : config.username(),
            config.token()));
      }
      try (var ignored = GitTlsContext.use(config.trustAllCertificates());
          Git git = clone.call()) {
        if (!config.ref().isBlank()) checkout(git, config.ref());
        ObjectId head = git.getRepository().resolve("HEAD");
        if (head == null) throw new IllegalStateException("Git repository has no HEAD revision");
        String revision = head.name();
        run.resolvedRevision(revision);
        runs.save(run);
        ingestFiles(checkout, run, source, config, revision,
            Duration.ofNanos(System.nanoTime() - started).toMillis());
      }
      root.status(FrontierStatus.FETCHED);
      items.save(root);
    } catch (Exception e) {
      root.status(FrontierStatus.FAILED);
      items.save(root);
      throw new IllegalStateException("Git acquisition failed: " + safe(e), e);
    } finally {
      deleteTree(checkout);
    }
  }

  private void ingestFiles(Path checkout, IngestionRun run,
      SourceConfiguration.GitRepository source, IngestionConfiguration.Git config,
      String revision, long durationMillis) throws Exception {
    int accepted = 0;
    try (var paths = Files.walk(checkout)) {
      for (Path file : paths.sorted().toList()) {
        if (accepted >= config.maxFiles()) break;
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(file))
          continue;
        String relative = checkout.relativize(file).toString().replace('\\', '/');
        if (relative.startsWith(".git/") || !supported(relative)
            || !included(relative, config.includePatterns(), config.excludePatterns()))
          continue;
        long size = Files.size(file);
        if (size < 1 || size > config.maxFileBytes()) continue;
        byte[] bytes = Files.readAllBytes(file);
        if (binary(bytes) || lfsPointer(bytes)) continue;

        String sourceUri = gitUri(source.repositoryUrl(), revision, relative);
        if (items.findByRunIdAndSourceUri(run.getId(), sourceUri).isPresent()) continue;
        UUID itemId = stable("git-item:" + run.getId() + ":" + sourceUri);
        UUID acquisitionId = stable("git-acquisition:" + run.getId() + ":" + sourceUri);
        SourceItem item = new SourceItem(itemId, run.getId(), relative, sourceUri, 1);
        item.assignCrate(run.getCrateId());
        item.status(FrontierStatus.FETCHED);
        items.save(item);

        String extension = relative.toLowerCase(Locale.ROOT).endsWith(".txt") ? ".txt" : ".md";
        String key = "crates/" + run.getCrateId() + "/runs/" + run.getId() + "/"
            + acquisitionId + extension;
        var saved = artifacts.put(key, new ByteArrayInputStream(bytes), config.maxFileBytes());
        AcquisitionRecord record =
            new AcquisitionRecord(acquisitionId, run.getId(), itemId, relative);
        record.assignCrate(run.getCrateId());
        record.success(sourceUri, 200,
            extension.equals(".md") ? "text/markdown; charset=UTF-8" : "text/plain; charset=UTF-8",
            "UTF-8", saved.key(), saved.sha256(), saved.length(), durationMillis);
        acquisitions.save(record);
        queue.publish(PipelineMessage.create(run.getCrateId(), WorkStage.PARSE,
            IngestionService.payload(run.getCrateId(), run.getId(), acquisitionId), run.getId(),
            run.getCrateId() + ":parse:" + acquisitionId, 50));
        accepted++;
      }
    }
  }

  private static void checkout(Git git, String ref) throws Exception {
    ObjectId id = null;
    for (String candidate : List.of(ref, "refs/heads/" + ref, "refs/tags/" + ref,
        "refs/remotes/origin/" + ref)) {
      id = git.getRepository().resolve(candidate);
      if (id != null) break;
    }
    if (id == null) throw new IllegalArgumentException("Git ref was not found");
    git.checkout().setName(id.name()).call();
  }

  private static boolean supported(String path) {
    String lower = path.toLowerCase(Locale.ROOT);
    return lower.endsWith(".md") || lower.endsWith(".markdown") || lower.endsWith(".txt");
  }

  private static boolean included(String path, List<String> includes, List<String> excludes) {
    boolean include = includes.stream().anyMatch(pattern -> glob(pattern).matcher(path).matches());
    return include && excludes.stream().noneMatch(pattern -> glob(pattern).matcher(path).matches());
  }

  private static Pattern glob(String value) {
    StringBuilder regex = new StringBuilder("^");
    String normalized = value.replace('\\', '/');
    for (int i = 0; i < normalized.length(); i++) {
      char c = normalized.charAt(i);
      if (c == '*') {
        if (i + 1 < normalized.length() && normalized.charAt(i + 1) == '*') {
          regex.append(".*");
          i++;
        } else regex.append("[^/]*");
      } else if (c == '?') regex.append("[^/]");
      else regex.append(Pattern.quote(String.valueOf(c)));
    }
    return Pattern.compile(regex.append("$").toString());
  }

  private static boolean binary(byte[] bytes) {
    int checked = Math.min(bytes.length, 8192);
    for (int i = 0; i < checked; i++) if (bytes[i] == 0) return true;
    String decoded = new String(bytes, StandardCharsets.UTF_8);
    return decoded.indexOf('\uFFFD') >= 0;
  }

  private static boolean lfsPointer(byte[] bytes) {
    String prefix = new String(bytes, 0, Math.min(bytes.length, 200), StandardCharsets.UTF_8);
    return prefix.startsWith("version https://git-lfs.github.com/spec/v1");
  }

  private static String gitUri(String repository, String revision, String path) {
    String encoded = java.net.URLEncoder.encode(path, StandardCharsets.UTF_8).replace("+", "%20")
        .replace("%2F", "/");
    return "git+" + repository + "@" + revision + "/" + encoded;
  }

  private static UUID stable(String value) {
    return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8));
  }

  private static void requireCrate(PipelinePayload payload, IngestionRun run, SourceItem item) {
    if (payload.crateId() != null && (!payload.crateId().equals(run.getCrateId())
        || !payload.crateId().equals(item.getCrateId())))
      throw new IllegalArgumentException("Pipeline message crosses crate boundary");
  }

  private static String safe(Exception e) {
    String value = e.getMessage();
    if (value == null || value.isBlank()) value = e.getClass().getSimpleName();
    return value.length() > 500 ? value.substring(0, 500) : value;
  }

  private static void deleteTree(Path root) {
    if (root == null || !Files.exists(root)) return;
    try (var paths = Files.walk(root)) {
      for (Path path : paths.sorted(Comparator.reverseOrder()).toList())
        Files.deleteIfExists(path);
    } catch (IOException ignored) {
    }
  }

  private static synchronized void installSafeHttpConnections(UrlPolicy policy) {
    HttpConnectionFactory current = HttpTransport.getConnectionFactory();
    if (current instanceof SafeHttpConnectionFactory safe) {
      safe.policy = policy;
    } else {
      HttpTransport.setConnectionFactory(new SafeHttpConnectionFactory(current, policy));
    }
  }

  /**
   * JGit asks its connection factory for redirect destinations as well as the initial endpoint.
   * Rechecking every requested URL prevents a public repository from redirecting a worker into a
   * private or metadata network.
   */
  private static final class SafeHttpConnectionFactory implements HttpConnectionFactory {
    private final HttpConnectionFactory delegate;
    private volatile UrlPolicy policy;

    private SafeHttpConnectionFactory(HttpConnectionFactory delegate, UrlPolicy policy) {
      this.delegate = delegate;
      this.policy = policy;
    }

    @Override
    public HttpConnection create(URL url) throws IOException {
      policy.assertSafe(url.toString());
      return relaxTls(delegate.create(url));
    }

    @Override
    public HttpConnection create(URL url, Proxy proxy) throws IOException {
      policy.assertSafe(url.toString());
      return relaxTls(delegate.create(url, proxy));
    }

    /** Opt-in only: applies to this thread's git operation when its job requested it. */
    private static HttpConnection relaxTls(HttpConnection connection) throws IOException {
      if (!GitTlsContext.trustAll()) return connection;
      try {
        connection.configure(null, InsecureSsl.trustAllManagers(), new SecureRandom());
        connection.setHostnameVerifier((hostname, session) -> true);
      } catch (java.security.GeneralSecurityException e) {
        throw new IOException("Failed to relax TLS validation for git clone", e);
      }
      return connection;
    }
  }
}
