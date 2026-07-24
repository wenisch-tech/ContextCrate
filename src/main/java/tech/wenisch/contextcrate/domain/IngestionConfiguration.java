package tech.wenisch.contextcrate.domain;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;

public record IngestionConfiguration(
    @Valid CrawlConfiguration webCrawler,
    @Valid Git git) {

  public static IngestionConfiguration web(CrawlConfiguration configuration) {
    return new IngestionConfiguration(configuration, null);
  }

  public static IngestionConfiguration git(Git configuration) {
    return new IngestionConfiguration(null, configuration);
  }

  public IngestionConfiguration withoutSecrets() {
    if (webCrawler != null) return IngestionConfiguration.web(webCrawler.withoutSecrets());
    return new IngestionConfiguration(null, git == null ? null : new Git(git.ref(), git.username(),
        null, git.includePatterns(), git.excludePatterns(), git.maxFiles(), git.maxFileBytes(),
        git.output()));
  }

  public record Git(
      String ref,
      String username,
      String token,
      List<String> includePatterns,
      List<String> excludePatterns,
      @Min(1) @Max(100_000) int maxFiles,
      @Min(1) long maxFileBytes,
      @Valid CrawlConfiguration.Output output) {
    public Git {
      ref = ref == null ? "" : ref.trim();
      includePatterns = includePatterns == null || includePatterns.isEmpty()
          ? List.of("**") : List.copyOf(includePatterns);
      excludePatterns = excludePatterns == null ? List.of() : List.copyOf(excludePatterns);
      maxFiles = maxFiles < 1 ? 10_000 : maxFiles;
      maxFileBytes = maxFileBytes < 1 ? 1_048_576 : maxFileBytes;
      output = output == null ? CrawlConfiguration.Output.defaults() : output;
    }

    public static Git defaults() {
      return new Git("", null, null, List.of("**"), List.of(), 10_000, 1_048_576,
          CrawlConfiguration.Output.defaults());
    }
  }
}
