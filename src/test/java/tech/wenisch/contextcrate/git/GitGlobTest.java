package tech.wenisch.contextcrate.git;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class GitGlobTest {
  private static boolean m(String glob, String path) {
    return GitSourceRetriever.glob(glob).matcher(path).matches();
  }

  @Test
  void defaultMatchesEverything() {
    assertThat(m("**", "a.txt")).isTrue();
    assertThat(m("**", "src/main/A.java")).isTrue();
  }

  @Test
  void bareNamesAndExtensionsMatchInAnyDirectory() {
    assertThat(m("*.java", "A.java")).isTrue();
    assertThat(m("*.java", "src/a/B.java")).isTrue();
    assertThat(m("package-lock.json", "web/package-lock.json")).isTrue();
    assertThat(m("*.java", "src/a/B.kt")).isFalse();
  }

  @Test
  void directoriesAndDoubleStarSlash() {
    assertThat(m("node_modules/", "node_modules/x/y.js")).isTrue();
    assertThat(m("**/generated/**", "generated/a.java")).isTrue();
    assertThat(m("**/generated/**", "a/b/generated/c.java")).isTrue();
    assertThat(m("docs/**", "docs/a/b.md")).isTrue();
    assertThat(m("docs/**", "other/docs/a.md")).isFalse();
  }
}
