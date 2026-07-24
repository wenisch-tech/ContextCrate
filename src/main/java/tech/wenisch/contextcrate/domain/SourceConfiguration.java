package tech.wenisch.contextcrate.domain;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import com.fasterxml.jackson.annotation.JsonAlias;

public record SourceConfiguration(
    @Valid Website website,
    @Valid GitRepository git) {

  public static SourceConfiguration https(String url) {
    return new SourceConfiguration(new Website(url), null);
  }

  public static SourceConfiguration git(String repositoryUrl) {
    return new SourceConfiguration(null, new GitRepository(repositoryUrl));
  }

  public SourceConfiguration withoutSecrets() {
    return new SourceConfiguration(
        website == null ? null : new Website(website.url()),
        git == null ? null : new GitRepository(git.repositoryUrl()));
  }

  public record Website(
      @NotBlank @JsonAlias("origin") String url) {}

  public record GitRepository(
      @NotBlank String repositoryUrl) {}
}
