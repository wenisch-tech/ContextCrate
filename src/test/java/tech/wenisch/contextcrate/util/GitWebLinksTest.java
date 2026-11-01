package tech.wenisch.contextcrate.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class GitWebLinksTest {
  private static final String SHA = "0123456789abcdef0123456789abcdef01234567";

  private static String uri(String repo) {
    return "git+" + repo + "@" + SHA + "/docs/My%20Guide.md";
  }

  @Test
  void github() {
    assertThat(GitWebLinks.viewUrl(uri("https://github.com/wenisch-tech/ContextCrate.git")))
        .isEqualTo("https://github.com/wenisch-tech/ContextCrate/blob/" + SHA + "/docs/My%20Guide.md");
  }

  @Test
  void gitlabIncludingSubgroups() {
    assertThat(GitWebLinks.viewUrl(uri("https://gitlab.com/group/sub/repo")))
        .isEqualTo("https://gitlab.com/group/sub/repo/-/blob/" + SHA + "/docs/My%20Guide.md");
  }

  @Test
  void forgejoGiteaAndCodebergAreTheDefaultLayout() {
    assertThat(GitWebLinks.viewUrl(uri("https://codeberg.org/o/r.git")))
        .isEqualTo("https://codeberg.org/o/r/src/commit/" + SHA + "/docs/My%20Guide.md");
    assertThat(GitWebLinks.viewUrl(uri("https://git.example.com/team/product/")))
        .isEqualTo("https://git.example.com/team/product/src/commit/" + SHA + "/docs/My%20Guide.md");
  }

  @Test
  void credentialsAndSshFormsAreNotLeaked() {
    assertThat(GitWebLinks.viewUrl(uri("https://user:secret@github.com/o/r.git")))
        .doesNotContain("secret").startsWith("https://github.com/o/r/blob/");
    assertThat(GitWebLinks.viewUrl(uri("git@github.com:o/r.git")))
        .startsWith("https://github.com/o/r/blob/");
    assertThat(GitWebLinks.viewUrl(uri("ssh://git@gitlab.example.com:2222/o/r.git")))
        .startsWith("https://gitlab.example.com/o/r/-/blob/");
  }

  @Test
  void nonGitUrisPassThroughOrAreRejected() {
    assertThat(GitWebLinks.viewUrl("https://example.com/page")).isEqualTo("https://example.com/page");
    assertThat(GitWebLinks.viewUrl("file:///x")).isNull();
    assertThat(GitWebLinks.viewUrl(null)).isNull();
  }
}
